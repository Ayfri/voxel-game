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
import kotlinx.coroutines.*

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

val SHADE_COLORS = Array(6) { d ->
	val shade = when (d) {
		0, 1 -> 0.8f
		2 -> 1.0f
		3 -> 0.5f
		4, 5 -> 0.75f
		else -> 1.0f
	}
	Color(shade, shade, shade, 1f)
}


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
	val chunkSize = world.config.chunkSize
	val worldChunks = world.chunks

	// Group columns into regions of REGION_SIZE x REGION_SIZE
	val regions = worldChunks.values.groupBy {
		val rx = if (it.cx >= 0) it.cx / REGION_SIZE else (it.cx - REGION_SIZE + 1) / REGION_SIZE
		val rz = if (it.cz >= 0) it.cz / REGION_SIZE else (it.cz - REGION_SIZE + 1) / REGION_SIZE
		rx to rz
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
suspend fun generateRegionMesh(
	world: World,
	voxelShader: KslShader,
	rx: Int,
	rz: Int
): Mesh<*>? {
	val chunkSize = world.config.chunkSize
	val worldHeight = world.config.worldHeight
	val worldChunks = world.chunks
	val minCx = rx * REGION_SIZE - 1
	val maxCx = (rx + 1) * REGION_SIZE
	val minCz = rz * REGION_SIZE - 1
	val maxCz = (rz + 1) * REGION_SIZE
	val cacheWidth = maxCx - minCx + 1
	val cacheDepth = maxCz - minCz + 1
	val chunkCache = arrayOfNulls<Chunk>(cacheWidth * worldHeight * cacheDepth)

	for (cx in minCx..maxCx) {
		for (cz in minCz..maxCz) {
			for (cy in 0 until worldHeight) {
				worldChunks[Vec3i(cx, cy, cz)]?.let {
					chunkCache[((cx - minCx) * worldHeight + cy) * cacheDepth + (cz - minCz)] = it
				}
			}
		}
	}

	val regionChunks = mutableListOf<Chunk>()
	for (cx in rx * REGION_SIZE until (rx + 1) * REGION_SIZE) {
		for (cz in rz * REGION_SIZE until (rz + 1) * REGION_SIZE) {
			for (cy in 0 until worldHeight) {
				chunkCache[((cx - minCx) * worldHeight + cy) * cacheDepth + (cz - minCz)]?.let { regionChunks.add(it) }
			}
		}
	}

	if (regionChunks.isEmpty() || regionChunks.all { it.isEmpty }) return null

	val mesh = Mesh(VOXEL_LAYOUT)
	mesh.shader = voxelShader
	mesh.isFrustumChecked = true
	val mask = IntArray(chunkSize * chunkSize)
	val n = MutableVec3f()
	val p0 = MutableVec3f()
	val p1 = MutableVec3f()
	val p2 = MutableVec3f()
	val p3 = MutableVec3f()
	val uv0 = MutableVec2f()
	val uv1 = MutableVec2f()
	val uv2 = MutableVec2f()
	val uv3 = MutableVec2f()

	// Pre-calculate block texture indices for all 6 directions to avoid redundant lookups
	val blockTexIndicesByDir = Array(6) { d ->
		IntArray(BLOCKS.size) { blockId -> BLOCKS[blockId].texIndices[d] }
	}

	// Use a single MeshBuilder and iterate chunks with yield for cancellation
	val builder = MeshBuilder(mesh.geometry)
	regionChunks.forEachIndexed { i, chunk ->
		if (i % 64 == 0) yield()
		val blocks = chunk.blocks ?: return@forEachIndexed

		val fx = (chunk.cx * chunkSize).toFloat()
		val fy = (chunk.cy * chunkSize).toFloat()
		val fz = (chunk.cz * chunkSize).toFloat()

		// Greedy meshing algorithm implementation

		// Iterate through all 6 directions (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z)
		for (d in 0..5) {
			val blockTexIndices = blockTexIndicesByDir[d]

			val axis = d / 2
			val isBackFace = d % 2 == 1

			val dx = if (axis == 0) (if (isBackFace) -1 else 1) else 0
			val dy = if (axis == 1) (if (isBackFace) -1 else 1) else 0
			val dz = if (axis == 2) (if (isBackFace) -1 else 1) else 0

			val ncx = chunk.cx + dx - minCx
			val ncy = chunk.cy + dy
			val ncz = chunk.cz + dz - minCz
			val neighborChunk =
				if (ncy in 0 until worldHeight && ncx in 0 until cacheWidth && ncz in 0 until cacheDepth) {
					chunkCache[(ncx * worldHeight + ncy) * cacheDepth + ncz]
				} else null

			val nBlocks = neighborChunk?.blocks

			for (slice in 0 until chunkSize) {
				mask.fill(-1)
				var maskEmpty = true

				// 1. Fill visibility mask for this slice
				when (axis) {
					0 -> { // X-axis (mask i=z, j=y)
						val nlx = slice + dx
						val useNeighbor = nlx < 0 || nlx >= chunkSize
						val nnlx = (nlx + chunkSize) % chunkSize
						for (j in 0 until chunkSize) {
							val maskBase = j * chunkSize
							val chunkBase = (slice * chunkSize + j) * chunkSize
							val nBase =
								if (useNeighbor) (nnlx * chunkSize + j) * chunkSize else (nlx * chunkSize + j) * chunkSize
							for (i in 0 until chunkSize) {
								val bId = blocks[chunkBase + i].toInt()
								if (bId != 255) {
									val isVisible = if (useNeighbor) {
										if (nBlocks != null) nBlocks[nBase + i].toInt() == 255 else true
									} else {
										blocks[nBase + i].toInt() == 255
									}
									if (isVisible) {
										mask[maskBase + i] = blockTexIndices[bId]
										maskEmpty = false
									}
								}
							}
						}
					}

					1 -> { // Y-axis (mask i=x, j=z)
						val nly = slice + dy
						val useNeighbor = nly < 0 || nly >= chunkSize
						val nnly = (nly + chunkSize) % chunkSize
						for (j in 0 until chunkSize) { // z
							val maskBase = j * chunkSize
							for (i in 0 until chunkSize) { // x
								val chunkIdx = (i * chunkSize + slice) * chunkSize + j
								val bId = blocks[chunkIdx].toInt()
								if (bId != 255) {
									val nIdx = (i * chunkSize + (if (useNeighbor) nnly else nly)) * chunkSize + j
									val isVisible = if (useNeighbor) {
										if (nBlocks != null) nBlocks[nIdx].toInt() == 255 else true
									} else {
										blocks[nIdx].toInt() == 255
									}
									if (isVisible) {
										mask[maskBase + i] = blockTexIndices[bId]
										maskEmpty = false
									}
								}
							}
						}
					}

					else -> { // Z-axis (mask i=x, j=y)
						val nlz = slice + dz
						val useNeighbor = nlz < 0 || nlz >= chunkSize
						val nnlz = (nlz + chunkSize) % chunkSize
						for (j in 0 until chunkSize) { // y
							val maskBase = j * chunkSize
							for (i in 0 until chunkSize) { // x
								val chunkIdx = (i * chunkSize + j) * chunkSize + slice
								val bId = blocks[chunkIdx].toInt()
								if (bId != 255) {
									val nIdx = (i * chunkSize + j) * chunkSize + (if (useNeighbor) nnlz else nlz)
									val isVisible = if (useNeighbor) {
										if (nBlocks != null) nBlocks[nIdx].toInt() == 255 else true
									} else {
										blocks[nIdx].toInt() == 255
									}
									if (isVisible) {
										mask[maskBase + i] = blockTexIndices[bId]
										maskEmpty = false
									}
								}
							}
						}
					}
				}

				if (maskEmpty) continue

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
								for (wi in 0 until w) {
									if (mask[nextRowOffset + i + wi] != texIndex) break@outer
								}
								h++
							}

							// Generate the merged quad
							builder.addGreedyFace(
								d,
								slice,
								i,
								j,
								w,
								h,
								texIndex,
								fx, fy, fz,
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
							for (hi in 0 until h) {
								val markRowOffset = (j + hi) * chunkSize
								for (wi in 0 until w) {
									mask[markRowOffset + i + wi] = -1
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
	if (mesh.geometry.numIndices > 0) {
		mesh.updateGeometryBounds()
		return mesh
	}
	mesh.release()
	return null
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
	fx: Float,
	fy: Float,
	fz: Float,
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
	val fw = w.toFloat()
	val fh = h.toFloat()
	val fs = slice.toFloat()

	color = SHADE_COLORS[d]
	vertexCustomizer = { layout -> set(layout.texIndex, texIndex.toFloat()) }

	when (d) {
		0 -> { // +X
			n.set(1f, 0f, 0f)
			p0.set(fx + fs + 1f, fy + v, fz + u)
			p1.set(fx + fs + 1f, fy + v, fz + u + w)
			p2.set(fx + fs + 1f, fy + v + h, fz + u + w)
			p3.set(fx + fs + 1f, fy + v + h, fz + u)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}

		1 -> { // -X
			n.set(-1f, 0f, 0f)
			p0.set(fx + fs, fy + v, fz + u + w)
			p1.set(fx + fs, fy + v, fz + u)
			p2.set(fx + fs, fy + v + h, fz + u)
			p3.set(fx + fs, fy + v + h, fz + u + w)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}

		2 -> { // +Y
			n.set(0f, 1f, 0f)
			p0.set(fx + u, fy + fs + 1f, fz + v)
			p1.set(fx + u + w, fy + fs + 1f, fz + v)
			p2.set(fx + u + w, fy + fs + 1f, fz + v + h)
			p3.set(fx + u, fy + fs + 1f, fz + v + h)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}

		3 -> { // -Y
			n.set(0f, -1f, 0f)
			p0.set(fx + u, fy + fs, fz + v + h)
			p1.set(fx + u + w, fy + fs, fz + v + h)
			p2.set(fx + u + w, fy + fs, fz + v)
			p3.set(fx + u, fy + fs, fz + v)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}

		4 -> { // +Z
			n.set(0f, 0f, 1f)
			p0.set(fx + u + w, fy + v, fz + fs + 1f)
			p1.set(fx + u, fy + v, fz + fs + 1f)
			p2.set(fx + u, fy + v + h, fz + fs + 1f)
			p3.set(fx + u + w, fy + v + h, fz + fs + 1f)
			addFace(p0, p1, p2, p3, n, fw, fh, uv0, uv1, uv2, uv3)
		}

		5 -> { // -Z
			n.set(0f, 0f, -1f)
			p0.set(fx + u, fy + v, fz + fs)
			p1.set(fx + u + w, fy + v, fz + fs)
			p2.set(fx + u + w, fy + v + h, fz + fs)
			p3.set(fx + u, fy + v + h, fz + fs)
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
	geometry.addIndex(i2)
	geometry.addIndex(i1)
	geometry.addIndex(i0)
	geometry.addIndex(i3)
	geometry.addIndex(i2)
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
