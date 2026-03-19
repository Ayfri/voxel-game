
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.util.BackendScope
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class WorldManager(
	val world: World,
	val player: Player,
	val worldNode: Node,
	val voxelShader: AtomicReference<KslShader?>
) {
	val activeRegions = ConcurrentHashMap<Pair<Int, Int>, Mesh<*>>()
	val clearWorldRequested = AtomicBoolean(false)
	val earlyRespawnRequested = AtomicBoolean(false)
	val firstColumnGenerated = AtomicBoolean(false)
	val isRefreshing = AtomicBoolean(false)
	val loadingRegions = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
	val meshingRegions = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
	val pendingUpdates = ConcurrentLinkedQueue<MeshUpdate>()
	val worldIsGenerated = AtomicBoolean(false)
	private var worldScope = CoroutineScope(BackendScope.job)

	private var frameCount = 0
	private val meshesToRelease = mutableListOf<Mesh<*>>()
	private val nextFrameRelease = mutableListOf<Mesh<*>>()

	data class MeshUpdate(val rCoord: Pair<Int, Int>, val newMesh: Mesh<*>?, val oldMesh: Mesh<*>?)

	fun refreshWorldMesh(regenerateWorld: Boolean = false) {
		if (isRefreshing.get()) return
		val shader = voxelShader.get() ?: return

		isRefreshing.set(true)
		firstColumnGenerated.set(false)

		if (regenerateWorld) {
			player.physicsEnabled = false
			player.isNoclip = true
			worldScope.cancel()
			worldScope = CoroutineScope(BackendScope.job)
			activeRegions.clear()
			loadingRegions.clear()
			pendingUpdates.clear()
			world.chunks.clear()
			clearWorldRequested.set(true)
			worldIsGenerated.set(false)
		}

		if (!worldIsGenerated.get()) {
			worldScope.launch {
				// Initial generation around player
				val chunkSize = world.config.chunkSize
				val playerCX = floor(player.position.x / chunkSize).toInt()
				val playerCZ = floor(player.position.z / chunkSize).toInt()

				val initialRadius = 2 // small radius to start fast
				val initialRegions = mutableSetOf<Pair<Int, Int>>()
				for (dx in -initialRadius..initialRadius) {
					for (dz in -initialRadius..initialRadius) {
						val cx = playerCX + dx
						val cz = playerCZ + dz

						if (!isWithinWorldLimit(cx, cz)) continue

						val rx = if (cx >= 0) cx / REGION_SIZE else (cx - REGION_SIZE + 1) / REGION_SIZE
						val rz = if (cz >= 0) cz / REGION_SIZE else (cz - REGION_SIZE + 1) / REGION_SIZE
						initialRegions.add(rx to rz)
					}
				}

				for (rCoord in initialRegions) {
					if (!activeRegions.containsKey(rCoord) && loadingRegions.add(rCoord)) {
						worldScope.launch {
							try {
								val rx = rCoord.first
								val rz = rCoord.second

								// Generate ALL chunks of this region + 1 chunk border before meshing in parallel
								coroutineScope {
									val jobs = mutableListOf<Deferred<Unit>>()
									for (lcx in -1..REGION_SIZE) {
										for (lcz in -1..REGION_SIZE) {
											val gcx = rx * REGION_SIZE + lcx
											val gcz = rz * REGION_SIZE + lcz

											if (!isWithinWorldLimit(gcx, gcz)) continue

											if (!world.isColumnGenerated(gcx, gcz)) {
												jobs.add(async { world.generateColumn(gcx, gcz) })
											}
										}
									}
									jobs.awaitAll()
								}

								val mesh = generateRegionMesh(world, shader, rx, rz)
								if (mesh != null) {
									val old = activeRegions.put(rCoord, mesh)
									pendingUpdates.add(MeshUpdate(rCoord, mesh, old))
									if (firstColumnGenerated.compareAndSet(false, true)) {
										earlyRespawnRequested.set(true)
									}
									triggerNeighborRemesh(rx, rz)
								}
							} finally {
								loadingRegions.remove(rCoord)
							}
						}
					}
				}
				worldIsGenerated.set(true)
				isRefreshing.set(false)
			}
		} else {
			isRefreshing.set(false)
		}
	}

	fun update(dt: Float) {
		// 1. Release meshes from the previous frame
		meshesToRelease.forEach { it.release() }
		meshesToRelease.clear()
		meshesToRelease.addAll(nextFrameRelease)
		nextFrameRelease.clear()

		if (clearWorldRequested.getAndSet(false)) {
			worldNode.children.forEach { if (it is Mesh<*>) nextFrameRelease.add(it) }
			worldNode.clearChildren()
		}

		if (earlyRespawnRequested.getAndSet(false)) {
			player.respawn()
			player.physicsEnabled = true
		}

		// Handle atomized mesh updates (fix for flickering)
		while (pendingUpdates.peek() != null) {
			val update = pendingUpdates.poll() ?: break
			if (update.newMesh != null) {
				if (activeRegions[update.rCoord] == update.newMesh) {
					worldNode.addNode(update.newMesh)
				} else {
					// This mesh was already superseded by a newer one
					nextFrameRelease.add(update.newMesh)
				}
			}
			if (update.oldMesh != null) {
				worldNode.removeNode(update.oldMesh)
				nextFrameRelease.add(update.oldMesh)
			}
		}

		// Dynamic world loading
		val shader = voxelShader.get()
		if (shader != null && worldIsGenerated.get()) {
			val chunkSize = world.config.chunkSize
			val playerCX = floor(player.position.x / chunkSize).toInt()
			val playerCZ = floor(player.position.z / chunkSize).toInt()

			val playerRX = if (playerCX >= 0) playerCX / REGION_SIZE else (playerCX - REGION_SIZE + 1) / REGION_SIZE
			val playerRZ = if (playerCZ >= 0) playerCZ / REGION_SIZE else (playerCZ - REGION_SIZE + 1) / REGION_SIZE

			val renderDistanceChunks = world.config.renderDistance
			val renderDistanceRegions = (renderDistanceChunks / REGION_SIZE) + 1
			val rSq = (renderDistanceChunks * chunkSize).let { (it * it).toFloat() }

			val radYaw = player.yaw.deg.rad
			val lookX = sin(radYaw)
			val lookZ = -cos(radYaw)

			val candidateRegions = mutableListOf<Pair<Pair<Int, Int>, Float>>()

			for (drx in -renderDistanceRegions..renderDistanceRegions) {
				for (drz in -renderDistanceRegions..renderDistanceRegions) {
					val rx = playerRX + drx
					val rz = playerRZ + drz

					// Circular check (center of region)
					val regionCenterX = (rx * REGION_SIZE + REGION_SIZE / 2f) * chunkSize
					val regionCenterZ = (rz * REGION_SIZE + REGION_SIZE / 2f) * chunkSize
					val dx = regionCenterX - player.position.x
					val dz = regionCenterZ - player.position.z
					val dSq = dx * dx + dz * dz
					if (dSq > rSq) continue

					val rCoord = rx to rz
					if (!activeRegions.containsKey(rCoord) && !loadingRegions.contains(rCoord)) {
						val dist = sqrt(dSq)
						var priority = dist
						if (dist > 0.1f) {
							val dot = (dx / dist) * lookX + (dz / dist) * lookZ
							// Bonus if aligned with look direction (dot > 0)
							priority -= dot * (chunkSize * REGION_SIZE * 2f)
						}
						candidateRegions.add(rCoord to priority)
					}
				}
			}

			// Sort by priority (lowest value first)
			candidateRegions.sortBy { it.second }

			for (candidate in candidateRegions.take(8)) { // Load up to 8 regions per update
				if (loadingRegions.size >= 16) break // Limit concurrent region loads
				val rCoord = candidate.first
				val rx = rCoord.first
				val rz = rCoord.second
				if (loadingRegions.add(rCoord)) {
					worldScope.launch {
						try {
							coroutineScope {
								val jobs = mutableListOf<Deferred<Unit>>()
								for (lcx in -1..REGION_SIZE) {
									for (lcz in -1..REGION_SIZE) {
										val gcx = rx * REGION_SIZE + lcx
										val gcz = rz * REGION_SIZE + lcz

										if (!isWithinWorldLimit(gcx, gcz)) continue

										if (!world.isColumnGenerated(gcx, gcz)) {
											jobs.add(async { world.generateColumn(gcx, gcz) })
										}
									}
								}
								jobs.awaitAll()
							}
							val mesh = generateRegionMesh(world, shader, rx, rz)
							if (mesh != null) {
								val old = activeRegions.put(rCoord, mesh)
								pendingUpdates.add(MeshUpdate(rCoord, mesh, old))
								triggerNeighborRemesh(rx, rz)
							}
						} finally {
							loadingRegions.remove(rCoord)
						}
					}
				}
			}

			// Unload distant regions
			val unloadDistance = (renderDistanceChunks + REGION_SIZE) * chunkSize
			val unloadDistanceSq = (unloadDistance * unloadDistance).toFloat()

			val regionIter = activeRegions.entries.iterator()
			while (regionIter.hasNext()) {
				val entry = regionIter.next()
				val rCoord = entry.key
				val rx = rCoord.first
				val rz = rCoord.second
				val regionCenterX = (rx * REGION_SIZE + REGION_SIZE / 2f) * chunkSize
				val regionCenterZ = (rz * REGION_SIZE + REGION_SIZE / 2f) * chunkSize
				val dx = regionCenterX - player.position.x
				val dz = regionCenterZ - player.position.z
				val dSq = dx * dx + dz * dz
				if (dSq > unloadDistanceSq) {
					regionIter.remove()
					pendingUpdates.add(MeshUpdate(rCoord, null, entry.value))
				}
			}

			// Clean up distant chunks from the world map to save memory
			if (frameCount++ % 100 == 0) {
				world.cleanupChunks(
					de.fabmax.kool.math.Vec3i(
						player.position.x.toInt(),
						player.position.y.toInt(),
						player.position.z.toInt()
					),
					world.config.renderDistance
				)
			}
		}

		// Independent removed meshes are now handled via pendingUpdates queue
	}

	private fun isWithinWorldLimit(cx: Int, cz: Int): Boolean {
		return true
	}

	private fun remeshRegion(rCoord: Pair<Int, Int>) {
		if (!activeRegions.containsKey(rCoord)) return
		val shader = voxelShader.get() ?: return
		if (meshingRegions.add(rCoord)) {
			worldScope.launch {
				try {
					val mesh = generateRegionMesh(world, shader, rCoord.first, rCoord.second)
					if (mesh != null) {
						val old = activeRegions.put(rCoord, mesh)
						pendingUpdates.add(MeshUpdate(rCoord, mesh, old))
					}
				} finally {
					meshingRegions.remove(rCoord)
				}
			}
		}
	}

	private fun triggerNeighborRemesh(rx: Int, rz: Int) {
		for (drx in -1..1) {
			for (drz in -1..1) {
				if (drx == 0 && drz == 0) continue
				remeshRegion(rx + drx to rz + drz)
			}
		}
	}
}
