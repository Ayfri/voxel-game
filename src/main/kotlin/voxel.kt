import de.fabmax.kool.math.Vec2f
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
import de.fabmax.kool.scene.geometry.MeshBuilder
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
 * Generates voxel world meshes using Greedy Meshing algorithm.
 * This significantly reduces the number of faces and vertices by merging adjacent
 * faces with the same texture, allowing for much larger worlds with better performance.
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
				columnChunks.forEach { chunk ->
					if (chunk.isEmpty) return@forEach

					// Greedy meshing algorithm implementation
					val mask = IntArray(chunkSize * chunkSize)

					// Iterate through all 6 directions (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z)
					for (d in 0..5) {
						val axis = d / 2
						val isBackFace = d % 2 == 1
						val u = (axis + 1) % 3
						val v = (axis + 2) % 3

						val dir = IntArray(3)
						dir[axis] = if (isBackFace) -1 else 1

						val neighborOffset = Vec3i(dir[0], dir[1], dir[2])
						val neighborChunk = worldChunks[Vec3i(
							chunk.cx + neighborOffset.x,
							chunk.cy + neighborOffset.y,
							chunk.cz + neighborOffset.z
						)]

						for (slice in 0 until chunkSize) {
							mask.fill(-1)

							// 1. Fill visibility mask for this slice
							for (j in 0 until chunkSize) {
								val rowOffset = j * chunkSize
								for (i in 0 until chunkSize) {
									val pos = IntArray(3)
									pos[axis] = slice
									pos[u] = i
									pos[v] = j

									val blockId = chunk.getBlock(pos[0], pos[1], pos[2])
									if (blockId != -1) {
										// Check if adjacent block is empty (visible face)
										val nextPos = IntArray(3) { pos[it] + dir[it] }
										val isVisible = if (nextPos[axis] in 0 until chunkSize) {
											chunk.getBlock(nextPos[0], nextPos[1], nextPos[2]) == -1
										} else {
											// Use local coordinates in neighbor chunk
											val lx = (nextPos[0] + chunkSize) % chunkSize
											val ly = (nextPos[1] + chunkSize) % chunkSize
											val lz = (nextPos[2] + chunkSize) % chunkSize
											(neighborChunk?.getBlock(lx, ly, lz) ?: -1) == -1
										}

										if (isVisible) {
											val block = BLOCKS[blockId]
											val texName = when (d) {
												0, 1 -> block.xSideTexture
												2 -> block.topTexture
												3 -> block.bottomTexture
												4, 5 -> block.zSideTexture
												else -> ""
											}
											mask[i + rowOffset] = TEXTURE_INDEX_MAP[texName] ?: 0
										}
									}
								}
							}

							// 2. Perform greedy merging on the mask
							for (j in 0 until chunkSize) {
								var i = 0
								while (i < chunkSize) {
									val texIndex = mask[j * chunkSize + i]
									if (texIndex != -1) {
										// Find max width
										var w = 1
										while (i + w < chunkSize && mask[j * chunkSize + i + w] == texIndex) {
											w++
										}

										// Find max height for this width
										var h = 1
										outer@ while (j + h < chunkSize) {
											val nextRowOffset = (j + h) * chunkSize
											for (w_idx in 0 until w) {
												if (mask[nextRowOffset + i + w_idx] != texIndex) break@outer
											}
											h++
										}

										// Generate the merged quad
										addGreedyFace(d, slice, i, j, w, h, texIndex, chunk)

										// Mark merged faces as processed
										for (h_idx in 0 until h) {
											val markRowOffset = (j + h_idx) * chunkSize
											for (w_idx in 0 until w) {
												mask[markRowOffset + i + w_idx] = -1
											}
										}
										i += w
									} else {
										i++
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
 * Helper to add a merged quad with correct orientation and UV repetition.
 */
private fun MeshBuilder<VoxelLayout>.addGreedyFace(
	d: Int,
	slice: Int,
	u: Int,
	v: Int,
	w: Int,
	h: Int,
	texIndex: Int,
	chunk: Chunk
) {
	val chunkSize = chunk.config.chunkSize
	val wx = (chunk.cx * chunkSize).toFloat()
	val wy = (chunk.cy * chunkSize).toFloat()
	val wz = (chunk.cz * chunkSize).toFloat()

	val shade = when (d) {
		0, 1 -> 0.8f
		2 -> 1.0f
		3 -> 0.5f
		4, 5 -> 0.75f
		else -> 1.0f
	}
	color = Color(shade, shade, shade, 1f)
	vertexCustomizer = { layout -> set(layout.texIndex, texIndex.toFloat()) }

	withTransform {
		when (d) {
			0 -> { // +X
				translate(wx + slice + 1.0f, wy + u + w / 2.0f, wz + v + h / 2.0f)
				rotate(90f.deg, Vec3f.Y_AXIS)
				addTexturedRect(h.toFloat(), w.toFloat())
			}

			1 -> { // -X
				translate(wx + slice, wy + u + w / 2.0f, wz + v + h / 2.0f)
				rotate((-90f).deg, Vec3f.Y_AXIS)
				addTexturedRect(h.toFloat(), w.toFloat())
			}

			2 -> { // +Y
				translate(wx + v + h / 2.0f, wy + slice + 1.0f, wz + u + w / 2.0f)
				rotate((-90f).deg, Vec3f.X_AXIS)
				addTexturedRect(h.toFloat(), w.toFloat())
			}

			3 -> { // -Y
				translate(wx + v + h / 2.0f, wy + slice, wz + u + w / 2.0f)
				rotate(90f.deg, Vec3f.X_AXIS)
				addTexturedRect(h.toFloat(), w.toFloat())
			}

			4 -> { // +Z
				translate(wx + u + w / 2.0f, wy + v + h / 2.0f, wz + slice + 1.0f)
				addTexturedRect(w.toFloat(), h.toFloat())
			}

			5 -> { // -Z
				translate(wx + u + w / 2.0f, wy + v + h / 2.0f, wz + slice)
				rotate(180f.deg, Vec3f.Y_AXIS)
				addTexturedRect(w.toFloat(), h.toFloat())
			}
		}
	}
}

/**
 * Adds a rectangle to the geometry with specific width/height and repeated UVs.
 */
private fun MeshBuilder<VoxelLayout>.addTexturedRect(width: Float, height: Float) {
	val i0 = vertex(Vec3f(-width / 2.0f, -height / 2.0f, 0f), Vec3f.Z_AXIS, Vec2f(0f, height))
	val i1 = vertex(Vec3f(width / 2.0f, -height / 2.0f, 0f), Vec3f.Z_AXIS, Vec2f(width, height))
	val i2 = vertex(Vec3f(width / 2.0f, height / 2.0f, 0f), Vec3f.Z_AXIS, Vec2f(width, 0f))
	val i3 = vertex(Vec3f(-width / 2.0f, height / 2.0f, 0f), Vec3f.Z_AXIS, Vec2f(0f, 0f))
	geometry.addIndices(i0, i1, i2, i0, i2, i3)
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
			val tex = texture3d("tBlockArray")
			val uNumLayers = uniformFloat1("uNumLayers")
			main {
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
