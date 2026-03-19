
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.util.BackendScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor

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
	val incrementalMeshes = ConcurrentLinkedQueue<Mesh<*>>()
	val isRefreshing = AtomicBoolean(false)
	val loadingRegions = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
	val removedMeshes = ConcurrentLinkedQueue<Mesh<*>>()
	val worldIsGenerated = AtomicBoolean(false)

	fun refreshWorldMesh(regenerateWorld: Boolean = false) {
		if (isRefreshing.get()) return
		val shader = voxelShader.get() ?: return

		isRefreshing.set(true)
		firstColumnGenerated.set(false)

		if (regenerateWorld) {
			player.physicsEnabled = false
			activeRegions.clear()
			loadingRegions.clear()
			clearWorldRequested.set(true)
			worldIsGenerated.set(false)
		}

		if (!worldIsGenerated.get()) {
			CoroutineScope(BackendScope.job).launch {
				// Initial generation around player
				val chunkSize = world.config.chunkSize
				val playerCX = floor(player.position.x / chunkSize).toInt()
				val playerCZ = floor(player.position.z / chunkSize).toInt()

				val initialRadius = 2 // small radius to start fast
				for (dx in -initialRadius..initialRadius) {
					for (dz in -initialRadius..initialRadius) {
						val cx = playerCX + dx
						val cz = playerCZ + dz

						if (!isWithinWorldLimit(cx, cz)) continue

						world.generateColumn(cx, cz)

						val rx = if (cx >= 0) cx / REGION_SIZE else (cx - REGION_SIZE + 1) / REGION_SIZE
						val rz = if (cz >= 0) cz / REGION_SIZE else (cz - REGION_SIZE + 1) / REGION_SIZE
						val rCoord = rx to rz

						if (!activeRegions.containsKey(rCoord) && loadingRegions.add(rCoord)) {
							val mesh = generateRegionMesh(world, shader, rx, rz)
							if (mesh != null) {
								activeRegions[rCoord] = mesh
								incrementalMeshes.add(mesh)
								if (firstColumnGenerated.compareAndSet(false, true)) {
									earlyRespawnRequested.set(true)
								}
							}
							loadingRegions.remove(rCoord)
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
		if (clearWorldRequested.getAndSet(false)) {
			worldNode.children.forEach { if (it is Mesh<*>) it.release() }
			worldNode.clearChildren()
		}

		if (earlyRespawnRequested.getAndSet(false)) {
			player.respawn()
			player.physicsEnabled = true
		}

		// Handle incremental mesh updates
		while (incrementalMeshes.peek() != null) {
			incrementalMeshes.poll()?.let { worldNode.addNode(it) }
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
					if (!activeRegions.containsKey(rCoord) && loadingRegions.add(rCoord)) {
						CoroutineScope(BackendScope.job).launch {
							for (lcx in 0 until REGION_SIZE) {
								for (lcz in 0 until REGION_SIZE) {
									val gcx = rx * REGION_SIZE + lcx
									val gcz = rz * REGION_SIZE + lcz

									if (!isWithinWorldLimit(gcx, gcz)) continue

									if (!world.isColumnGenerated(gcx, gcz)) {
										world.generateColumn(gcx, gcz)
									}
								}
							}
							val mesh = generateRegionMesh(world, shader, rx, rz)
							if (mesh != null) {
								activeRegions[rCoord] = mesh
								incrementalMeshes.add(mesh)
							}
							loadingRegions.remove(rCoord)
						}
					}
				}
			}

			// Unload distant regions
			val unloadDistance = (renderDistanceChunks + REGION_SIZE) * chunkSize
			val unloadDistanceSq = (unloadDistance * unloadDistance).toFloat()
			val toRemove = activeRegions.keys.filter { (rx, rz) ->
				val regionCenterX = (rx * REGION_SIZE + REGION_SIZE / 2f) * chunkSize
				val regionCenterZ = (rz * REGION_SIZE + REGION_SIZE / 2f) * chunkSize
				val dx = regionCenterX - player.position.x
				val dz = regionCenterZ - player.position.z
				val dSq = dx * dx + dz * dz
				dSq > unloadDistanceSq
			}
			toRemove.forEach { rCoord ->
				activeRegions.remove(rCoord)?.let { removedMeshes.add(it) }
			}
		}

		// Handle removed meshes
		while (removedMeshes.peek() != null) {
			removedMeshes.poll()?.let {
				worldNode.removeNode(it)
				it.release()
			}
		}
	}

	private fun isWithinWorldLimit(cx: Int, cz: Int): Boolean {
		val limitChunks = WORLD_LIMIT_CHUNKS
		return cx >= -limitChunks && cx < limitChunks &&
			cz >= -limitChunks && cz < limitChunks
	}
}
