import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.Vec3i
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

data class RaycastResult(
	val blockPos: Vec3i,
	val face: Vec3i,
	val hitPos: Vec3f,
	val distance: Float
)

fun raycast(world: World, origin: Vec3f, direction: Vec3f, maxDistance: Float): RaycastResult? {
	var x = floor(origin.x).toInt()
	var y = floor(origin.y).toInt()
	var z = floor(origin.z).toInt()

	val dx = direction.x
	val dy = direction.y
	val dz = direction.z

	val stepX = sign(dx).toInt()
	val stepY = sign(dy).toInt()
	val stepZ = sign(dz).toInt()

	val tDeltaX = if (dx == 0f) Float.MAX_VALUE else abs(1f / dx)
	val tDeltaY = if (dy == 0f) Float.MAX_VALUE else abs(1f / dy)
	val tDeltaZ = if (dz == 0f) Float.MAX_VALUE else abs(1f / dz)

	var tMaxX = if (dx == 0f) Float.MAX_VALUE else (if (stepX > 0) (x + 1) - origin.x else origin.x - x) * tDeltaX
	var tMaxY = if (dy == 0f) Float.MAX_VALUE else (if (stepY > 0) (y + 1) - origin.y else origin.y - y) * tDeltaY
	var tMaxZ = if (dz == 0f) Float.MAX_VALUE else (if (stepZ > 0) (z + 1) - origin.z else origin.z - z) * tDeltaZ

	var face = Vec3i(0, 0, 0)
	var dist = 0f

	// Check starting block
	val startBlockId = world.getBlockIdAt(x, y, z)
	if (startBlockId in BLOCKS.indices) {
		return RaycastResult(Vec3i(x, y, z), face, origin, 0f)
	}

	while (dist < maxDistance) {
		if (tMaxX < tMaxY) {
			if (tMaxX < tMaxZ) {
				dist = tMaxX
				tMaxX += tDeltaX
				x += stepX
				face = Vec3i(-stepX, 0, 0)
			} else {
				dist = tMaxZ
				tMaxZ += tDeltaZ
				z += stepZ
				face = Vec3i(0, 0, -stepZ)
			}
		} else {
			if (tMaxY < tMaxZ) {
				dist = tMaxY
				tMaxY += tDeltaY
				y += stepY
				face = Vec3i(0, -stepY, 0)
			} else {
				dist = tMaxZ
				tMaxZ += tDeltaZ
				z += stepZ
				face = Vec3i(0, 0, -stepZ)
			}
		}

		if (dist > maxDistance) break

		val blockId = world.getBlockIdAt(x, y, z)
		if (blockId != -1 && blockId != 0xFFFFFF) {
			if (blockId >= 0 && blockId < BLOCKS.size) {
				return RaycastResult(Vec3i(x, y, z), face, origin + direction * dist, dist)
			}
		}
	}
	return null
}
