import de.fabmax.kool.math.MutableVec2f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.Vec3i
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

const val REGION_SIZE = 4

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

	// Group columns into regions of REGION_SIZE x REGION_SIZE
	val regions = worldChunks.values.groupBy {
		(it.cx / REGION_SIZE) to (it.cz / REGION_SIZE)
	}

	val sortedRegionKeys = regions.keys.sortedBy { (rx, rz) ->
		val dx = (rx + 0.5f) * REGION_SIZE * chunkSize - centerX
		val dz = (rz + 0.5f) * REGION_SIZE * chunkSize - centerZ
		dx * dx + dz * dz
	}

	sortedRegionKeys.map { key ->
		async {
			generateRegionMesh(world, voxelShader, key.first, key.second)
		}
	}.awaitAll().filterNotNull()
}

/**
 * Generates a mesh for a region of columns at (rx, rz).
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun generateRegionMesh(
	world: World,
	voxelShader: KslShader,
	rx: Int,
	rz: Int
): Mesh<*>? {
	val worldChunks = world.chunks
	val chunkSize = world.config.chunkSize

	val regionChunks = mutableListOf<Chunk>()
	for (cx in rx * REGION_SIZE until (rx + 1) * REGION_SIZE) {
		for (cz in rz * REGION_SIZE until (rz + 1) * REGION_SIZE) {
			for (cy in 0 until world.config.worldHeight) {
				worldChunks[Vec3i(cx, cy, cz)]?.let { regionChunks.add(it) }
			}
		}
	}

	if (regionChunks.isEmpty() || regionChunks.all { it.isEmpty }) return null

	val mesh = Mesh(VOXEL_LAYOUT)
	mesh.shader = voxelShader
	mesh.isFrustumChecked = true
	val mask = getMask(chunkSize)
	val p0 = MutableVec3f()
	val p1 = MutableVec3f()
	val p2 = MutableVec3f()
	val p3 = MutableVec3f()
	val n = MutableVec3f()
	val uv0 = MutableVec2f()
	val uv1 = MutableVec2f()
	val uv2 = MutableVec2f()
	val uv3 = MutableVec2f()
	mesh.generate {
		regionChunks.forEach { chunk ->
			if (chunk.isEmpty) return@forEach

			// Greedy meshing algorithm implementation

			// Iterate through all 6 directions (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z)
			for (d in 0..5) {
				val axis = d / 2
				val isBackFace = d % 2 == 1

				val dx = if (axis == 0) (if (isBackFace) -1 else 1) else 0
				val dy = if (axis == 1) (if (isBackFace) -1 else 1) else 0
				val dz = if (axis == 2) (if (isBackFace) -1 else 1) else 0

				val neighborChunk = worldChunks[Vec3i(
					chunk.cx + dx,
					chunk.cy + dy,
					chunk.cz + dz
				)]

				for (slice in 0 until chunkSize) {
					mask.fill(-1)

					// 1. Fill visibility mask for this slice
					for (j in 0 until chunkSize) {
						val rowOffset = j * chunkSize
						for (i in 0 until chunkSize) {
							val lx = when (axis) {
								0 -> slice
								1 -> j
								else -> i
							}
							val ly = when (axis) {
								0 -> i
								1 -> slice
								else -> j
							}
							val lz = when (axis) {
								0 -> j
								1 -> i
								else -> slice
							}

							val blockId = chunk.getBlock(lx, ly, lz)
							if (blockId != -1) {
								val nlx = lx + dx
								val nly = ly + dy
								val nlz = lz + dz

								val isVisible =
									if (nlx in 0 until chunkSize && nly in 0 until chunkSize && nlz in 0 until chunkSize) {
										chunk.getBlock(nlx, nly, nlz) == -1
								} else {
										val nnlx = (nlx + chunkSize) % chunkSize
										val nnly = (nly + chunkSize) % chunkSize
										val nnlz = (nlz + chunkSize) % chunkSize
										(neighborChunk?.getBlock(nnlx, nnly, nnlz) ?: -1) == -1
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
								addGreedyFace(
									d,
									slice,
									i,
									j,
									w,
									h,
									texIndex,
									chunk,
									p0,
									p1,
									p2,
									p3,
									n,
									uv0,
									uv1,
									uv2,
									uv3
								)

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
	return mesh
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
	chunk: Chunk,
	p0: MutableVec3f,
	p1: MutableVec3f,
	p2: MutableVec3f,
	p3: MutableVec3f,
	n: MutableVec3f,
	uv0: MutableVec2f,
	uv1: MutableVec2f,
	uv2: MutableVec2f,
	uv3: MutableVec2f
) {
	val chunkSize = chunk.config.chunkSize
	val fx = (chunk.cx * chunkSize).toFloat()
	val fy = (chunk.cy * chunkSize).toFloat()
	val fz = (chunk.cz * chunkSize).toFloat()

	val fw = w.toFloat()
	val fh = h.toFloat()
	val fs = slice.toFloat()

	val shade = when (d) {
		0, 1 -> 0.8f
		2 -> 1.0f
		3 -> 0.5f
		4, 5 -> 0.75f
		else -> 1.0f
	}
	color = Color(shade, shade, shade, 1f)
	vertexCustomizer = { layout -> set(layout.texIndex, texIndex.toFloat()) }

	when (d) {
		0 -> { // +X
			n.set(1f, 0f, 0f)
			p0.set(fx + fs + 1f, fy + u, fz + v + h)
			p1.set(fx + fs + 1f, fy + u, fz + v)
			p2.set(fx + fs + 1f, fy + u + w, fz + v)
			p3.set(fx + fs + 1f, fy + u + w, fz + v + h)
			addFace(p0, p1, p2, p3, n, fh, fw, uv0, uv1, uv2, uv3)
		}

		1 -> { // -X
			n.set(-1f, 0f, 0f)
			p0.set(fx + fs, fy + u, fz + v)
			p1.set(fx + fs, fy + u, fz + v + h)
			p2.set(fx + fs, fy + u + w, fz + v + h)
			p3.set(fx + fs, fy + u + w, fz + v)
			addFace(p0, p1, p2, p3, n, fh, fw, uv0, uv1, uv2, uv3)
		}

		2 -> { // +Y
			n.set(0f, 1f, 0f)
			p0.set(fx + v, fy + fs + 1f, fz + u + w)
			p1.set(fx + v + h, fy + fs + 1f, fz + u + w)
			p2.set(fx + v + h, fy + fs + 1f, fz + u)
			p3.set(fx + v, fy + fs + 1f, fz + u)
			addFace(p0, p1, p2, p3, n, fh, fw, uv0, uv1, uv2, uv3)
		}

		3 -> { // -Y
			n.set(0f, -1f, 0f)
			p0.set(fx + v + h, fy + fs, fz + u + w)
			p1.set(fx + v, fy + fs, fz + u + w)
			p2.set(fx + v, fy + fs, fz + u)
			p3.set(fx + v + h, fy + fs, fz + u)
			addFace(p0, p1, p2, p3, n, fh, fw, uv0, uv1, uv2, uv3)
		}

		4 -> { // +Z
			n.set(0f, 0f, 1f)
			p0.set(fx + u, fy + v, fz + fs + 1f)
			p1.set(fx + u + w, fy + v, fz + fs + 1f)
			p2.set(fx + u + w, fy + v + h, fz + fs + 1f)
			p3.set(fx + u, fy + v + h, fz + fs + 1f)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}

		5 -> { // -Z
			n.set(0f, 0f, -1f)
			p0.set(fx + u + w, fy + v, fz + fs)
			p1.set(fx + u, fy + v, fz + fs)
			p2.set(fx + u, fy + v + h, fz + fs)
			p3.set(fx + u + w, fy + v + h, fz + fs)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}
	}
}

private fun MeshBuilder<VoxelLayout>.addFace(
	p0: Vec3f, p1: Vec3f, p2: Vec3f, p3: Vec3f,
	n: Vec3f,
	uMax: Float, vMax: Float,
	uv0: MutableVec2f,
	uv1: MutableVec2f,
	uv2: MutableVec2f,
	uv3: MutableVec2f
) {
	val i0 = vertex(p0, n, uv0.set(0f, vMax))
	val i1 = vertex(p1, n, uv1.set(uMax, vMax))
	val i2 = vertex(p2, n, uv2.set(uMax, 0f))
	val i3 = vertex(p3, n, uv3.set(0f, 0f))
	geometry.addIndex(i0)
	geometry.addIndex(i1)
	geometry.addIndex(i2)
	geometry.addIndex(i0)
	geometry.addIndex(i2)
	geometry.addIndex(i3)
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

private val maskThreadLocal = ThreadLocal<IntArray>()

/**
 * Gets a cached IntArray from ThreadLocal or creates a new one if it doesn't exist.
 * The array is guaranteed to be at least as large as chunkSize * chunkSize.
 */
private fun getMask(chunkSize: Int): IntArray {
	val size = chunkSize * chunkSize
	var mask = maskThreadLocal.get()
	if (mask == null || mask.size < size) {
		mask = IntArray(size)
		maskThreadLocal.set(mask)
	}
	return mask
}
