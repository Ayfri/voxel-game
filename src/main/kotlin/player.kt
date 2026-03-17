import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.UniversalKeyCode
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.deg
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class Player(val world: World) {
	val position = MutableVec3f(100f, 200f, 100f)
	val velocity = MutableVec3f()
	var onGround = false

	var yaw = 0f
	var pitch = 0f

	val height = 4f
	val width = 2f
	val depth = 2f

	val walkSpeed = 10f
	val jumpSpeed = 12f
	val gravity = 30f

	private val pressedKeys = mutableSetOf<Int>()

	data object Controls {
		val forward = listOf(UniversalKeyCode('W'), UniversalKeyCode('Z'), KeyboardInput.KEY_CURSOR_UP)
		val backward = listOf(UniversalKeyCode('S'), KeyboardInput.KEY_CURSOR_DOWN)
		val left = listOf(UniversalKeyCode('A'), UniversalKeyCode('Q'), KeyboardInput.KEY_CURSOR_LEFT)
		val right = listOf(UniversalKeyCode('D'), KeyboardInput.KEY_CURSOR_RIGHT)
		val jump = listOf(UniversalKeyCode(' '))

		val allMovement = forward + backward + left + right + jump
	}

	fun update(dt: Float) {
		// Apply gravity
		velocity.y -= gravity * dt
		if (velocity.y < -50f) velocity.y = -50f // Terminal velocity

		// Horizontal movement
		handleInput()

		// Movement with collision
		move(velocity.x * dt, 0f, 0f)
		move(0f, 0f, velocity.z * dt)

		val dy = velocity.y * dt
		position.y += dy
		if (isColliding()) {
			if (velocity.y < 0) {
				onGround = true
			}
			position.y -= dy
			velocity.y = 0f
		} else {
			onGround = false
		}
	}

	private fun handleInput() {
		var moveX = 0f
		var moveZ = 0f

		if (Controls.forward.any { isKeyPressed(it) }) moveZ -= 1f
		if (Controls.backward.any { isKeyPressed(it) }) moveZ += 1f
		if (Controls.left.any { isKeyPressed(it) }) moveX -= 1f
		if (Controls.right.any { isKeyPressed(it) }) moveX += 1f

		if (moveX != 0f || moveZ != 0f) {
			val length = sqrt(moveX * moveX + moveZ * moveZ)
			val localX = moveX / length
			val localZ = moveZ / length

			val radYaw = yaw.deg.rad
			val cosYaw = cos(radYaw)
			val sinYaw = sin(radYaw)

			velocity.x = (localX * cosYaw - localZ * sinYaw) * walkSpeed
			velocity.z = (localX * sinYaw + localZ * cosYaw) * walkSpeed
		} else {
			velocity.x = 0f
			velocity.z = 0f
		}

		if (onGround && Controls.jump.any { isKeyPressed(it) }) {
			velocity.y = jumpSpeed
			onGround = false
		}
	}

	private fun isKeyPressed(keyCode: KeyCode) = pressedKeys.contains(keyCode.code)

	fun onKeyEvent(ev: KeyEvent) {
		if (ev.isPressed) pressedKeys.add(ev.keyCode.code)
		if (ev.isReleased) pressedKeys.remove(ev.keyCode.code)
	}

	private fun move(dx: Float, dy: Float, dz: Float) {
		position.x += dx
		position.y += dy
		position.z += dz

		if (isColliding()) {
			position.x -= dx
			position.y -= dy
			position.z -= dz
			if (dx != 0f) velocity.x = 0f
			if (dz != 0f) velocity.z = 0f
		}
	}

	fun isColliding(): Boolean {
		val xMin = position.x - width / 2f
		val xMax = position.x + width / 2f
		val yMin = position.y
		val yMax = position.y + height
		val zMin = position.z - depth / 2f
		val zMax = position.z + depth / 2f

		val ixMin = floor(xMin).toInt()
		val ixMax = floor(xMax).toInt()
		val iyMin = floor(yMin).toInt()
		val iyMax = floor(yMax).toInt()
		val izMin = floor(zMin).toInt()
		val izMax = floor(zMax).toInt()

		for (ix in ixMin..ixMax) {
			for (iy in iyMin..iyMax) {
				for (iz in izMin..izMax) {
					if (world.getBlockIdAt(ix, iy, iz) != -1) {
						if (xMin < ix + 1f && xMax > ix &&
							yMin < iy + 1f && yMax > iy &&
							zMin < iz + 1f && zMax > iz
						) {
							return true
						}
					}
				}
			}
		}
		return false
	}
}
