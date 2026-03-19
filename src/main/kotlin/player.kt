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
	var cameraYOffset = 0f
	val cameraYOffsetLerpSpeed = 10f
	val depth = 1.9f
	val gravity = 50f
	val height = 3.8f
	var isNoclip = false
	val jumpSpeed = 16f
	var noclipSpeed = 20f
	var onGround = false
	var physicsEnabled = false
	var pitch = 0f
	val position = MutableVec3f()
	val stepHeight = 1.1f
	val velocity = MutableVec3f()
	val walkSpeed = 10f
	val width = 1.9f
	var yaw = 0f

	init {
		respawn()
	}

	private val pressedKeys = mutableSetOf<Int>()

	data object Controls {
		val backward = listOf(UniversalKeyCode('S'), KeyboardInput.KEY_CURSOR_DOWN)
		val descend = listOf(KeyboardInput.KEY_SHIFT_LEFT, KeyboardInput.KEY_SHIFT_RIGHT)
		val forward = listOf(UniversalKeyCode('W'), UniversalKeyCode('Z'), KeyboardInput.KEY_CURSOR_UP)
		val jump = listOf(UniversalKeyCode(' '))
		val left = listOf(UniversalKeyCode('A'), UniversalKeyCode('Q'), KeyboardInput.KEY_CURSOR_LEFT)
		val respawn = listOf(KeyboardInput.KEY_ENTER)
		val right = listOf(UniversalKeyCode('D'), KeyboardInput.KEY_CURSOR_RIGHT)

		val allMovement = backward + descend + forward + jump + left + respawn + right
	}

	fun update(dt: Float) {
		if (!physicsEnabled) return

		if (isNoclip) {
			handleNoclipInput(dt)
			return
		}

		// Apply gravity
		velocity.y -= gravity * dt
		if (velocity.y < -100f) velocity.y = -100f // Terminal velocity

		// Horizontal movement
		handleInput()

		// Movement with collision
		move(velocity.x * dt, 0f, 0f)
		move(0f, 0f, velocity.z * dt)

		// Smooth camera height
		if (cameraYOffset != 0f) {
			val delta = cameraYOffsetLerpSpeed * dt
			if (cameraYOffset > 0f) {
				cameraYOffset = (cameraYOffset - delta).coerceAtLeast(0f)
			} else {
				cameraYOffset = (cameraYOffset + delta).coerceAtMost(0f)
			}
		}

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

			velocity.x = (localX * cosYaw - localZ * sinYaw) * (if (isNoclip) noclipSpeed else walkSpeed)
			velocity.z = (localX * sinYaw + localZ * cosYaw) * (if (isNoclip) noclipSpeed else walkSpeed)
		} else {
			velocity.x = 0f
			velocity.z = 0f
		}

		if (!isNoclip && onGround && Controls.jump.any { isKeyPressed(it) }) {
			velocity.y = jumpSpeed
			onGround = false
		}
	}

	private fun handleNoclipInput(dt: Float) {
		var moveX = 0f
		var moveY = 0f
		var moveZ = 0f

		if (Controls.forward.any { isKeyPressed(it) }) moveZ -= 1f
		if (Controls.backward.any { isKeyPressed(it) }) moveZ += 1f
		if (Controls.left.any { isKeyPressed(it) }) moveX -= 1f
		if (Controls.right.any { isKeyPressed(it) }) moveX += 1f
		if (Controls.jump.any { isKeyPressed(it) }) moveY += 1f
		if (Controls.descend.any { isKeyPressed(it) }) moveY -= 1f

		if (moveX != 0f || moveY != 0f || moveZ != 0f) {
			val length = sqrt(moveX * moveX + moveY * moveY + moveZ * moveZ)
			val localX = moveX / length
			val localY = moveY / length
			val localZ = moveZ / length

			val radYaw = yaw.deg.rad
			val cosYaw = cos(radYaw)
			val sinYaw = sin(radYaw)

			val vx = (localX * cosYaw - localZ * sinYaw) * noclipSpeed
			val vy = localY * noclipSpeed
			val vz = (localX * sinYaw + localZ * cosYaw) * noclipSpeed

			position.x += vx * dt
			position.y += vy * dt
			position.z += vz * dt
		}
		velocity.set(0f, 0f, 0f)
	}

	private fun isKeyPressed(keyCode: KeyCode) = pressedKeys.contains(keyCode.code)

	fun onKeyEvent(ev: KeyEvent) {
		if (ev.isPressed) pressedKeys.add(ev.keyCode.code)
		if (ev.isReleased) pressedKeys.remove(ev.keyCode.code)
	}

	fun respawn() {
		val centerX = 0
		val centerZ = 0
		val surfaceY = world.getSurfaceY(centerX, centerZ)
		position.set(centerX.toFloat() + 0.5f, surfaceY.toFloat() + 1.1f, centerZ.toFloat() + 0.5f)
		velocity.set(0f, 0f, 0f)
		yaw = 0f
		pitch = 0f
		onGround = false
		cameraYOffset = 0f
	}

	private fun move(dx: Float, dy: Float, dz: Float) {
		val oldX = position.x
		val oldY = position.y
		val oldZ = position.z

		position.x += dx
		position.y += dy
		position.z += dz

		if (isColliding()) {
			if (onGround && (dx != 0f || dz != 0f) && dy == 0f) {
				position.y += stepHeight
				if (isColliding()) {
					position.x = oldX
					position.y = oldY
					position.z = oldZ
					if (dx != 0f) velocity.x = 0f
					if (dz != 0f) velocity.z = 0f
				} else {
					cameraYOffset -= stepHeight
				}
			} else {
				position.x = oldX
				position.y = oldY
				position.z = oldZ
				if (dx != 0f) velocity.x = 0f
				if (dz != 0f) velocity.z = 0f
			}
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
