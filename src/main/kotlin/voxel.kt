import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.Vec3i
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.modules.ksl.lang.*
import de.fabmax.kool.pipeline.Attribute
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.DepthCompareOp
import de.fabmax.kool.pipeline.GpuType
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.Struct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

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
 * Generates voxel world meshes.
 * Meshes are generated per 3D chunk for better performance and frustum culling.
 */
@OptIn(ExperimentalUnsignedTypes::class)
suspend fun generateWorldMeshes(
	world: World,
	voxelShader: KslShader,
	centerX: Float = 0f,
	centerZ: Float = 0f
): List<Mesh<*>> = withContext(Dispatchers.Default) {
	val worldChunks = world.chunks
	val chunkSize = world.config.chunkSize

	val columns = worldChunks.values.groupBy { it.cx to it.cz }

	val sortedColumnKeys = columns.keys.sortedBy { (cx, cz) ->
		val dx = (cx + 0.5f) * chunkSize - centerX
		val dz = (cz + 0.5f) * chunkSize - centerZ
		dx * dx + dz * dz
	}

	sortedColumnKeys.map { key ->
		async {
			val columnChunks = columns[key] ?: return@async null
			if (columnChunks.all { it.isEmpty }) return@async null

			val mesh = Mesh(VOXEL_LAYOUT)
			mesh.shader = voxelShader
			mesh.generate {
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
						rect {
							size.set(1f, 1f)
						}
					}
				}

				columnChunks.forEach { chunk ->
					if (chunk.isEmpty) return@forEach

					val nx = worldChunks[Vec3i(chunk.cx - 1, chunk.cy, chunk.cz)]
					val px = worldChunks[Vec3i(chunk.cx + 1, chunk.cy, chunk.cz)]
					val ny = worldChunks[Vec3i(chunk.cx, chunk.cy - 1, chunk.cz)]
					val py = worldChunks[Vec3i(chunk.cx, chunk.cy + 1, chunk.cz)]
					val nz = worldChunks[Vec3i(chunk.cx, chunk.cy, chunk.cz - 1)]
					val pz = worldChunks[Vec3i(chunk.cx, chunk.cy, chunk.cz + 1)]

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
								val isTopVisible = if (ly < chunkSize - 1) {
									chunk.getBlock(lx, ly + 1, lz) == -1
								} else {
									(py?.getBlock(lx, 0, lz) ?: -1) == -1
								}
								if (isTopVisible) {
									drawFace(block.topTexture, 1.0f) {
										translate(x + 0.5f, y + 1.0f, z + 0.5f)
										rotate((-90f).deg, Vec3f.X_AXIS)
									}
								}

								// Bottom face (-Y)
								val isBottomVisible = if (ly > 0) {
									chunk.getBlock(lx, ly - 1, lz) == -1
								} else {
									(ny?.getBlock(lx, chunkSize - 1, lz) ?: -1) == -1
								}
								if (isBottomVisible) {
									drawFace(block.bottomTexture, 0.5f) {
										translate(x + 0.5f, y, z + 0.5f)
										rotate(90f.deg, Vec3f.X_AXIS)
									}
								}

								// Side faces (+X, -X, +Z, -Z)
								val isPXVisible = if (lx < chunkSize - 1) {
									chunk.getBlock(lx + 1, ly, lz) == -1
								} else {
									(px?.getBlock(0, ly, lz) ?: -1) == -1
								}
								if (isPXVisible) {
									drawFace(block.xSideTexture, 0.8f) {
										translate(x + 1.0f, y + 0.5f, z + 0.5f)
										rotate(90f.deg, Vec3f.Y_AXIS)
									}
								}

								val isNXVisible = if (lx > 0) {
									chunk.getBlock(lx - 1, ly, lz) == -1
								} else {
									(nx?.getBlock(chunkSize - 1, ly, lz) ?: -1) == -1
								}
								if (isNXVisible) {
									drawFace(block.xSideTexture, 0.8f) {
										translate(x, y + 0.5f, z + 0.5f)
										rotate((-90f).deg, Vec3f.Y_AXIS)
									}
								}

								val isPZVisible = if (lz < chunkSize - 1) {
									chunk.getBlock(lx, ly, lz + 1) == -1
								} else {
									(pz?.getBlock(lx, ly, 0) ?: -1) == -1
								}
								if (isPZVisible) {
									drawFace(block.zSideTexture, 0.75f) {
										translate(x + 0.5f, y + 0.5f, z + 1.0f)
									}
								}

								val isNZVisible = if (lz > 0) {
									chunk.getBlock(lx, ly, lz - 1) == -1
								} else {
									(nz?.getBlock(lx, ly, chunkSize - 1) ?: -1) == -1
								}
								if (isNZVisible) {
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
			mesh
		}
	}.awaitAll().filterNotNull()
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
