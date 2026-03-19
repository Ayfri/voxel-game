import de.fabmax.kool.util.Color

/**
 * Defines a block type with its associated textures for different faces.
 */
data class Block(
	val bottomTexture: String,
	val name: String,
	val topTexture: String,
	val xSideTexture: String,
	val zSideTexture: String,
) {
	// Pre-calculated texture indices for the 3D texture array
	val texIndices = IntArray(6) { 0 }
	// Simple block with the same texture on all faces.
	constructor(name: String, texture: String) : this(texture, name, texture, texture, texture)

	// Block with separate textures for top and bottom.
	constructor(name: String, topTexture: String, bottomTexture: String) : this(
		bottomTexture,
		name,
		topTexture,
		topTexture,
		bottomTexture
	)

	// Block with separate textures for top, bottom, and all sides.
	constructor(name: String, topTexture: String, bottomTexture: String, sideTexture: String) : this(
		bottomTexture,
		name,
		topTexture,
		sideTexture,
		sideTexture
	)
}

/**
 * Registry of all available block types.
 */
val BLOCKS = listOf(
	Block("grass", "grass", "dirt", "grass-side"),
	Block("dirt", "dirt"),
	Block("stone", "stone"),
)

/**
 * A specific instance of a block at a world position.
 */
data class BlockInstance(val blockId: Int, val x: Int, val y: Int, val z: Int) {
	val block get() = lazy { BLOCKS[blockId] }

	fun getTopTexture() = block.value.topTexture
	fun getBottomTexture() = block.value.bottomTexture
	fun getXSideTexture() = block.value.xSideTexture
	fun getZSideTexture() = block.value.zSideTexture

	// Basic directional shading factors to provide more depth to the scene.
	fun getTopShade() = 1f
	fun getBottomShade() = 0.5f
	fun getXSideShade() = 0.8f
	fun getZSideShade() = 0.75f
}

// Global background/sky color.
val BACKGROUND = Color(0.3f, 0.7f, 0.9f)
