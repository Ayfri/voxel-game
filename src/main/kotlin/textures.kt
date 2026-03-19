import de.fabmax.kool.Assets
import de.fabmax.kool.MimeType
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Uint8BufferImpl
import java.io.File

/**
 * Stores the vertical index (slice) of each block texture in the 3D texture array.
 */
val TEXTURE_INDEX_MAP = mutableMapOf<String, Int>()

/**
 * Loads all PNG images from the given directory and creates a 3D texture array.
 * Texture arrays allow the shader to sample different block textures using a 
 * single texture binding and a z-coordinate.
 */
suspend fun buildTextureArray(blocksDir: File): Texture3d {
	// Collect and sort all block textures to maintain a consistent indexing order.
	val files = blocksDir.listFiles { _, name -> name.endsWith(".png") }
		?.sortedBy { it.name }
		?: emptyList()

	val loadedImages = files.map { file ->
		// Load local file into memory and parse it as a 2D image.
		val bytes = file.readBytes()
		val buffer = Uint8BufferImpl(bytes)
		Assets.loadImageFromBuffer(buffer, MimeType.forFileName(file.name))
	}

	// Register each texture's name to its index in the array.
	files.forEachIndexed { index, file ->
		TEXTURE_INDEX_MAP[file.nameWithoutExtension] = index
	}

	// Update block texture indices
	BLOCKS.forEach { block ->
		block.texIndices[0] = TEXTURE_INDEX_MAP[block.xSideTexture] ?: 0 // +X
		block.texIndices[1] = TEXTURE_INDEX_MAP[block.xSideTexture] ?: 0 // -X
		block.texIndices[2] = TEXTURE_INDEX_MAP[block.topTexture] ?: 0   // +Y
		block.texIndices[3] = TEXTURE_INDEX_MAP[block.bottomTexture] ?: 0 // -Y
		block.texIndices[4] = TEXTURE_INDEX_MAP[block.zSideTexture] ?: 0 // +Z
		block.texIndices[5] = TEXTURE_INDEX_MAP[block.zSideTexture] ?: 0 // -Z
	}

	// Combine individual images into a single 3D texture array with nearest filtering
	// to preserve the pixel-art look and REPEAT to support large merged quads.
	return ImageData2dArray(loadedImages).toTexture(
		mipMapping = MipMapping.Full,
		samplerSettings = SamplerSettings(
			minFilter = FilterMethod.LINEAR,
			magFilter = FilterMethod.NEAREST,
			addressModeU = AddressMode.REPEAT,
			addressModeV = AddressMode.REPEAT,
			addressModeW = AddressMode.CLAMP_TO_EDGE,
			maxAnisotropy = 16,
		)
	)
}