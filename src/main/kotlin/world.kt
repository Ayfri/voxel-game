import de.fabmax.kool.math.Vec3i
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * World configuration for defining dimensions and generation parameters.
 */
data class WorldConfig(
	val chunkSize: Int = 16,
	val worldWidth: Int = 16,   // Width in chunks (16 * 16 = 256 blocks)
	val worldDepth: Int = 16,   // Depth in chunks (16 * 16 = 256 blocks)
	val worldHeight: Int = 24,  // Height in chunks (24 * 16 = 384 blocks)
	val seed: Long = Random.nextLong(),
	val stoneBlockId: Int = 2,
	val grassBlockId: Int = 0,
	val dirtBlockId: Int = 1,
) {
	constructor(width: Int, height: Int, seed: Long = Random.nextLong()) : this(
		worldWidth = width,
		worldDepth = width,
		worldHeight = height,
		seed = seed,
	)
}

/**
 * A Chunk represents a 16x16x16 cube of blocks.
 * Using 3D chunking allows for better mesh culling and larger worlds.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Chunk(val cx: Int, val cy: Int, val cz: Int, val config: WorldConfig) {
	// Stores all solid blocks in this chunk.
	// 255 represents air (no block).
	val blocks = UByteArray(config.chunkSize * config.chunkSize * config.chunkSize) { 255u }
	var isEmpty = true
		private set

	private fun getIndex(lx: Int, ly: Int, lz: Int) = (lx * config.chunkSize + ly) * config.chunkSize + lz

	fun setBlock(lx: Int, ly: Int, lz: Int, id: Int) {
		blocks[getIndex(lx, ly, lz)] = id.toUByte()
		if (id != -1) isEmpty = false
	}

	fun getBlock(lx: Int, ly: Int, lz: Int): Int {
		val id = blocks[getIndex(lx, ly, lz)].toInt()
		return if (id == 255) -1 else id
	}

	fun generate(seaLevel: Int) {
		val startX = cx * config.chunkSize
		val startY = cy * config.chunkSize
		val startZ = cz * config.chunkSize

		for (lx in 0..<config.chunkSize) {
			for (lz in 0..<config.chunkSize) {
				val worldX = startX + lx
				val worldZ = startZ + lz

				// Apply light warp to break linear noise artifacts and "low poly" look
				val wx = Noise.noise(worldX / 120.0, worldZ / 120.0) * 15.0
				val wz = Noise.noise(worldZ / 120.0, worldX / 120.0 + 100.0) * 15.0
				val tx = worldX + wx
				val tz = worldZ + wz

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
				) * 1.55 - 0.1 // Plus de hauteur possible

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

				val surfaceY = (seaLevel + combinedNoise * (seaLevel * 0.56)).toInt()
					.coerceIn(0, config.worldHeight * config.chunkSize - 1)

				for (ly in 0..<config.chunkSize) {
					val worldY = startY + ly
					if (worldY <= surfaceY) {
						val id = when {
							worldY == surfaceY -> config.grassBlockId
							worldY > surfaceY - 3 -> config.dirtBlockId
							else -> config.stoneBlockId
						}
						setBlock(lx, ly, lz, id)
					}
				}
			}
		}
	}
}

data class World(var config: WorldConfig) {
	// Map keyed by chunk coordinates (cx, cy, cz).
	val chunks = ConcurrentHashMap<Vec3i, Chunk>()
	var isGenerating = false

	suspend fun generateAll() = withContext(Dispatchers.Default) {
		isGenerating = true
		Noise.setSeed(config.seed)
		chunks.clear()
		val seaLevel = (config.worldHeight * config.chunkSize) / 2

		val centerX = config.worldWidth / 2
		val centerZ = config.worldDepth / 2

		val coords = mutableListOf<Pair<Int, Int>>()
		for (cx in 0..<config.worldWidth) {
			for (cz in 0..<config.worldDepth) {
				coords.add(cx to cz)
			}
		}
		coords.sortBy { (cx, cz) ->
			val dx = cx - centerX
			val dz = cz - centerZ
			dx * dx + dz * dz
		}

		coords.map { (cx, cz) ->
			async {
				for (cy in 0..<config.worldHeight) {
					val chunk = Chunk(cx, cy, cz, config)
					chunk.generate(seaLevel)
					chunks[Vec3i(cx, cy, cz)] = chunk
				}
			}
		}.awaitAll()
		isGenerating = false
	}

	fun getBlockIdAt(x: Int, y: Int, z: Int): Int {
		val cx = if (x >= 0) x / config.chunkSize else (x + 1) / config.chunkSize - 1
		val cy = if (y >= 0) y / config.chunkSize else (y + 1) / config.chunkSize - 1
		val cz = if (z >= 0) z / config.chunkSize else (z + 1) / config.chunkSize - 1

		val lx = x - cx * config.chunkSize
		val ly = y - cy * config.chunkSize
		val lz = z - cz * config.chunkSize

		return chunks[Vec3i(cx, cy, cz)]?.getBlock(lx, ly, lz) ?: -1
	}

	// For compatibility during transition, we keep getBlockAt but it will return a temporary BlockInstance
	fun getBlockAt(x: Int, y: Int, z: Int): BlockInstance? {
		val id = getBlockIdAt(x, y, z)
		return if (id == -1) null else BlockInstance(x, y, z, id)
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
}