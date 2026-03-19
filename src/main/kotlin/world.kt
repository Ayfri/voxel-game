
import de.fabmax.kool.math.Vec3i
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * World configuration for defining dimensions and generation parameters.
 */
data class WorldConfig(
	val chunkSize: Int = 16,
	val dirtBlockId: Int = 1,
	val grassBlockId: Int = 0,
	val renderDistance: Int = 12,
	val seed: Long = Random.nextLong(),
	val stoneBlockId: Int = 2,
	val worldHeight: Int = 16,
) {
	constructor(width: Int, height: Int, seed: Long = Random.nextLong()) : this(
		worldHeight = height,
		renderDistance = width / 2,
		seed = seed,
	)
}

/**
 * A Chunk represents a 16x16x16 cube of blocks.
 * Using 3D chunking allows for better mesh culling and larger worlds.
 */
@OptIn(ExperimentalUnsignedTypes::class)
data class Chunk(
	val config: WorldConfig,
	val cx: Int,
	val cy: Int,
	val cz: Int
) {
	private var _blocks: UByteArray? = null
	var isEmpty = true
		private set

	private fun getIndex(lx: Int, ly: Int, lz: Int) = (lx * config.chunkSize + ly) * config.chunkSize + lz

	fun setBlock(lx: Int, ly: Int, lz: Int, id: Int) {
		val uId = id.toUByte()
		if (uId == 255u.toUByte() && _blocks == null) return

		val b =
			_blocks ?: UByteArray(config.chunkSize * config.chunkSize * config.chunkSize) { 255u }.also { _blocks = it }
		b[getIndex(lx, ly, lz)] = uId
		if (uId != 255u.toUByte()) isEmpty = false
	}

	fun getBlock(lx: Int, ly: Int, lz: Int): Int {
		val b = _blocks ?: return -1
		val id = b[getIndex(lx, ly, lz)].toInt()
		return if (id == 255) -1 else id
	}

	suspend fun generateFromSurface(columnSurfaces: IntArray) {
		val chunkSize = config.chunkSize
		val chunkSizeSq = chunkSize * chunkSize
		val startY = cy * chunkSize
		val endY = startY + chunkSize - 1

		for (lx in 0..<chunkSize) {
			if (lx % 4 == 0) yield()
			for (lz in 0..<chunkSize) {
				val surfaceY = columnSurfaces[lx * chunkSize + lz]

				// Quick check: if the entire chunk is above surfaceY, it's empty
				if (startY > surfaceY) continue

				val blocks = _blocks ?: UByteArray(chunkSize * chunkSize * chunkSize) { 255u }.also { _blocks = it }
				isEmpty = false
				
				val baseIndex = lx * chunkSizeSq + lz
				val fillMaxY = if (surfaceY > endY) endY else surfaceY

				for (worldY in startY..fillMaxY) {
					val ly = worldY - startY
					val id = when {
						worldY == surfaceY -> config.grassBlockId
						worldY > surfaceY - 3 -> config.dirtBlockId
						else -> config.stoneBlockId
					}
					blocks[baseIndex + ly * chunkSize] = id.toUByte()
				}
			}
		}
	}
}

data class World(var config: WorldConfig) {
	// Map keyed by chunk coordinates (cx, cy, cz).
	val chunks = ConcurrentHashMap<Vec3i, Chunk>()
	private val generatingColumns = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
	var isGenerating = false

	fun isColumnGenerated(cx: Int, cz: Int): Boolean {
		if (!isWithinWorldLimitChunks(cx, cz)) return false
		return chunks.containsKey(Vec3i(cx, 0, cz))
	}

	suspend fun generateColumn(cx: Int, cz: Int, onColumnGenerated: suspend (Int, Int) -> Unit = { _, _ -> }) {
		if (!isWithinWorldLimitChunks(cx, cz)) return
		if (generatingColumns.contains(cx to cz)) return
		generatingColumns.add(cx to cz)
		
		val seaLevel = (config.worldHeight * config.chunkSize) / 2
		val chunkSize = config.chunkSize
		val columnSurfaces = IntArray(chunkSize * chunkSize)
		val startX = cx * chunkSize
		val startZ = cz * chunkSize

		for (lx in 0 until chunkSize) {
			for (lz in 0 until chunkSize) {
				columnSurfaces[lx * chunkSize + lz] = calculateSurfaceY(startX + lx, startZ + lz, seaLevel)
			}
		}

		for (cy in 0..<config.worldHeight) {
			val chunk = Chunk(config, cx, cy, cz)
			chunk.generateFromSurface(columnSurfaces)
			chunks[Vec3i(cx, cy, cz)] = chunk
		}

		generatingColumns.remove(cx to cz)
		onColumnGenerated(cx, cz)
	}

	fun getSurfaceY(x: Int, z: Int): Int {
		val seaLevel = (config.worldHeight * config.chunkSize) / 2
		return calculateSurfaceY(x, z, seaLevel)
	}

	private fun calculateSurfaceY(worldX: Int, worldZ: Int, seaLevel: Int): Int {
		val x = worldX.toDouble()
		val z = worldZ.toDouble()

		// Apply light warp to break linear noise artifacts and "low poly" look
		val wx = Noise.noise(x / 120.0, z / 120.0) * 15.0
		val wz = Noise.noise(z / 120.0, x / 120.0 + 100.0) * 15.0
		val tx = x + wx
		val tz = z + wz

		// Selector noise to choose between biomes (plains vs mountains)
		val selector = Noise.fractal(
			tx / 1200.0,
			tz / 1200.0,
			octaves = 2
		) * 0.5 + 0.5 // Range [0.0, 1.0]

		// Large global variation
		val globalVariation = Noise.fractal(
			tx / 3000.0,
			tz / 3000.0,
			octaves = 1
		) * 0.25

		// Base plains terrain (flat but slightly uneven)
		val plainsBase = Noise.fractal(
			tx / 400.0,
			tz / 400.0
		) * 0.15 + 0.1

		// Ridged mountains (steep hills)
		val mountainsBase = Noise.ridged(
			tx / 800.0,
			tz / 800.0,
			octaves = 6,
			persistence = 0.45
		) * 1.55 - 0.1 // More height possible

		// Combine them based on selector
		// If selector is high, we have more mountains
		var combinedNoise = if (selector < 0.2) {
			plainsBase
		} else if (selector > 0.6) {
			mountainsBase
		} else {
			// Smooth transition
			var t = (selector - 0.2) / 0.4
			t = t * t * (3.0 - 2.0 * t) // Smoothstep
			Noise.lerp(plainsBase, mountainsBase, t)
		}

		// Add global variation and fine detail noise
		combinedNoise += globalVariation
		combinedNoise += Noise.fractal(tx / 40.0, tz / 40.0, octaves = 2) * 0.04

		return (seaLevel + combinedNoise * (seaLevel * 0.56)).toInt()
			.coerceIn(0, config.worldHeight * config.chunkSize - 1)
	}

	fun getBlockIdAt(x: Int, y: Int, z: Int): Int {
		if (!isWithinWorldLimit(x, z)) return -1
		val cx = if (x >= 0) x / config.chunkSize else (x + 1) / config.chunkSize - 1
		val cy = if (y >= 0) y / config.chunkSize else (y + 1) / config.chunkSize - 1
		val cz = if (z >= 0) z / config.chunkSize else (z + 1) / config.chunkSize - 1

		val lx = x - cx * config.chunkSize
		val ly = y - cy * config.chunkSize
		val lz = z - cz * config.chunkSize

		return chunks[Vec3i(cx, cy, cz)]?.getBlock(lx, ly, lz) ?: -1
	}

	fun setBlockIdAt(x: Int, y: Int, z: Int, id: Int) {
		if (!isWithinWorldLimit(x, z)) return
		val cx = if (x >= 0) x / config.chunkSize else (x + 1) / config.chunkSize - 1
		val cy = if (y >= 0) y / config.chunkSize else (y + 1) / config.chunkSize - 1
		val cz = if (z >= 0) z / config.chunkSize else (z + 1) / config.chunkSize - 1

		val lx = x - cx * config.chunkSize
		val ly = y - cy * config.chunkSize
		val lz = z - cz * config.chunkSize

		val chunk = chunks.getOrPut(Vec3i(cx, cy, cz)) { Chunk(config, cx, cy, cz) }
		chunk.setBlock(lx, ly, lz, id)
	}

	// For compatibility during transition, we keep getBlockAt but it will return a temporary BlockInstance
	fun getBlockAt(x: Int, y: Int, z: Int): BlockInstance? {
		val id = getBlockIdAt(x, y, z)
		return if (id == -1) null else BlockInstance(id, x, y, z)
	}

	fun getHighestBlockY(x: Int, z: Int, range: Int = 1): Int {
		var maxY = 0
		for (ix in (x - range)..(x + range)) {
			for (iz in (z - range)..(z + range)) {
				for (y in (config.worldHeight * config.chunkSize - 1) downTo 0) {
					if (getBlockIdAt(ix, y, iz) != -1) {
						if (y > maxY) maxY = y
						break
					}
				}
			}
		}
		return maxY
	}

	private fun isWithinWorldLimit(x: Int, z: Int): Boolean {
		return x >= -WORLD_LIMIT && x < WORLD_LIMIT &&
			z >= -WORLD_LIMIT && z < WORLD_LIMIT
	}

	private fun isWithinWorldLimitChunks(cx: Int, cz: Int): Boolean {
		return cx >= -WORLD_LIMIT_CHUNKS && cx < WORLD_LIMIT_CHUNKS &&
			cz >= -WORLD_LIMIT_CHUNKS && cz < WORLD_LIMIT_CHUNKS
	}
}