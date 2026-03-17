import de.fabmax.kool.math.Vec3i
import kotlin.random.Random

/**
 * World configuration for defining dimensions and generation parameters.
 */
data class WorldConfig(
	val chunkSize: Int = 16,
	val worldWidth: Int = 16,   // Width in chunks (16 * 16 = 256 blocks)
	val worldDepth: Int = 16,   // Depth in chunks (16 * 16 = 256 blocks)
	val worldHeight: Int = 8,  // Height in chunks (8 * 16 = 128 blocks)
	val seed: Long = Random.nextLong(),
	val stoneBlockId: Int = 2,
	val grassBlockId: Int = 0,
	val dirtBlockId: Int = 1,
)

/**
 * A Chunk represents a 16x16x16 cube of blocks.
 * Using 3D chunking allows for better mesh culling and larger worlds.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Chunk(val cx: Int, val cy: Int, val cz: Int, val config: WorldConfig) {
	// Stores all solid blocks in this chunk.
	// 255 represents air (no block).
	val blocks = UByteArray(config.chunkSize * config.chunkSize * config.chunkSize) { 255u }

	private fun getIndex(lx: Int, ly: Int, lz: Int) = (lx * config.chunkSize + ly) * config.chunkSize + lz

	fun setBlock(lx: Int, ly: Int, lz: Int, id: Int) {
		blocks[getIndex(lx, ly, lz)] = id.toUByte()
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

				val noiseValue = Noise.fractal(
					worldX.toDouble() / 100.0,
					worldZ.toDouble() / 100.0
				)
				val surfaceY = (seaLevel + noiseValue * (seaLevel / 2)).toInt()

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
	val chunks = mutableMapOf<Vec3i, Chunk>()

	fun generateAll() {
		Noise.setSeed(config.seed)
		chunks.clear()
		val seaLevel = (config.worldHeight * config.chunkSize) / 2
		for (cx in 0..<config.worldWidth) {
			for (cy in 0..<config.worldHeight) {
				for (cz in 0..<config.worldDepth) {
					val chunk = Chunk(cx, cy, cz, config)
					chunk.generate(seaLevel)
					chunks[Vec3i(cx, cy, cz)] = chunk
				}
			}
		}
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
}