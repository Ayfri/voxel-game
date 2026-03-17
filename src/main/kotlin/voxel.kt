import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.modules.ksl.lang.*
import de.fabmax.kool.pipeline.Attribute
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.DepthCompareOp
import de.fabmax.kool.pipeline.GpuType
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.addMesh
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.Struct

val ATTRIBUTE_TEX_INDEX = Attribute("aTexIndex", GpuType.Float1)

/**
 * Custom memory layout for voxel vertices, adding a texture index for the array texture.
 */
class VoxelLayout : Struct("VoxelLayout", MemoryLayout.TightlyPacked) {
	val position = float3(Attribute.POSITIONS.name)
	val normal = float3(Attribute.NORMALS.name)
	val color = float4(Attribute.COLORS.name)
	val texCoord = float2(Attribute.TEXTURE_COORDS.name)
	val texIndex = float1(ATTRIBUTE_TEX_INDEX.name)
}

val VOXEL_LAYOUT = VoxelLayout()

/**
 * Adds the voxel world meshes to the given Node.
 * Meshes are generated per 3D chunk for better performance and frustum culling.
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun Node.addVoxelWorld(world: World, voxelShader: KslShader) {
	world.chunks.values.forEach { chunk ->
		val chunkSize = world.config.chunkSize

		// Check if chunk is empty before adding a mesh
		var isEmpty = true
		for (i in chunk.blocks.indices) {
			if (chunk.blocks[i] != 255u.toUByte()) {
				isEmpty = false
				break
			}
		}
		if (isEmpty) return@forEach

		addMesh(VOXEL_LAYOUT) {
			shader = voxelShader
			generate {
				/**
				 * Helper to draw a single quad face with specific texture and shading.
				 */
				fun drawFace(textureName: String, shadeFactor: Float, transform: () -> Unit) {
					val sliceIndex = TEXTURE_INDEX_MAP[textureName]?.toFloat() ?: 0f
					color = Color(shadeFactor, shadeFactor, shadeFactor, 1f)
					vertexCustomizer = { layout ->
						set(layout.texIndex, sliceIndex)
					}
					withTransform {
						transform()
						// Standard 1x1 quad centered at origin (XY plane).
						// Normal is +Z by default.
						rect {
							size.set(1f, 1f)
						}
					}
				}

				for (lx in 0..<chunkSize) {
					for (ly in 0..<chunkSize) {
						for (lz in 0..<chunkSize) {
							val id = chunk.getBlock(lx, ly, lz)
							if (id == -1) continue

							val block = BLOCKS[id]
							val wx = chunk.cx * chunkSize + lx
							val wy = chunk.cy * chunkSize + ly
							val wz = chunk.cz * chunkSize + lz

							val x = wx.toFloat()
							val y = wy.toFloat()
							val z = wz.toFloat()

							// Top face (+Y)
							if (world.getBlockIdAt(wx, wy + 1, wz) == -1) {
								drawFace(block.topTexture, 1.0f) {
									translate(x + 0.5f, y + 1.0f, z + 0.5f)
									rotate((-90f).deg, Vec3f.X_AXIS)
								}
							}
							// Bottom face (-Y)
							if (world.getBlockIdAt(wx, wy - 1, wz) == -1) {
								drawFace(block.bottomTexture, 0.5f) {
									translate(x + 0.5f, y, z + 0.5f)
									rotate(90f.deg, Vec3f.X_AXIS)
								}
							}
							// Side faces (+X, -X, +Z, -Z)
							if (world.getBlockIdAt(wx + 1, wy, wz) == -1) {
								drawFace(block.xSideTexture, 0.8f) {
									translate(x + 1.0f, y + 0.5f, z + 0.5f)
									rotate(90f.deg, Vec3f.Y_AXIS)
								}
							}
							if (world.getBlockIdAt(wx - 1, wy, wz) == -1) {
								drawFace(block.xSideTexture, 0.8f) {
									translate(x, y + 0.5f, z + 0.5f)
									rotate((-90f).deg, Vec3f.Y_AXIS)
								}
							}
							if (world.getBlockIdAt(wx, wy, wz + 1) == -1) {
								drawFace(block.zSideTexture, 0.75f) {
									translate(x + 0.5f, y + 0.5f, z + 1.0f)
								}
							}
							if (world.getBlockIdAt(wx, wy, wz - 1) == -1) {
								drawFace(block.zSideTexture, 0.75f) {
									translate(x + 0.5f, y + 0.5f, z)
									rotate(180f.deg, Vec3f.Y_AXIS)
								}
							}
						}
					}
				}
			}
		}
	}
}

/**
 * Creates the KSL shader for rendering the voxel world using a 3D texture array.
 */
fun createVoxelShader(): KslShader {
	val shader = KslShader("VoxelArrayShader") {
		val uv = interStageFloat2("uv")
		val vColor = interStageFloat4("vColor")
		val texIndex = interStageFloat1("texIndex")

		vertexStage {
			main {
				uv.input set vertexAttribFloat2(VOXEL_LAYOUT.texCoord.name)
				vColor.input set vertexAttribFloat4(VOXEL_LAYOUT.color.name)
				texIndex.input set vertexAttribFloat1(VOXEL_LAYOUT.texIndex.name)

				outPosition set mvpMatrix().matrix * float4Value(
					vertexAttribFloat3(VOXEL_LAYOUT.position.name),
					1f
				)
			}
		}
		fragmentStage {
			main {
				val tex = texture3d("tBlockArray")
				val uNumLayers = uniformFloat1("uNumLayers")

				val sampleCoords = float3Value(
					uv.output.x,
					uv.output.y,
					(texIndex.output + 0.5f.const) / uNumLayers
				)
				colorOutput(sampleTexture(tex, sampleCoords) * vColor.output)
			}
		}
	}
	// Configure pipeline for correct voxel rendering.
	shader.pipelineConfig = shader.pipelineConfig.copy(
		depthTest = DepthCompareOp.LESS,
		cullMethod = CullMethod.CULL_BACK_FACES
	)
	return shader
}
