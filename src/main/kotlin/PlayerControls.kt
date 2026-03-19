import de.fabmax.kool.input.*
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.PerspectiveCamera
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

const val NOCLIP_MIN_SPEED = 1f
const val NOCLIP_MAX_SPEED = 1000f

class PlayerControls(
	val player: Player,
	val camera: PerspectiveCamera,
	val world: World,
	val worldManager: WorldManager
) {
	private val keyListeners = mutableListOf<InputStack.SimpleKeyListener>()

	init {
		setupKeyListeners()
	}

	private fun setupKeyListeners() {
		// Register player movement keys
		Player.Controls.allMovement.flatMap {
			if (it.isLocal) listOf(it) else listOf(it, LocalKeyCode(it.code))
		}.forEach { keyCode ->
			keyListeners += KeyboardInput.addKeyListener(keyCode, "Move ${keyCode.name}") {
				player.onKeyEvent(it)
			}
		}

		keyListeners += KeyboardInput.addKeyListener(KeyboardInput.KEY_ESC, "Unlock cursor") {
			if (it.isPressed) {
				PointerInput.cursorMode = CursorMode.NORMAL
			}
		}

		keyListeners += KeyboardInput.addKeyListener(UniversalKeyCode('R'), "Regenerate") {
			if (it.isPressed && !worldManager.isRefreshing.get()) {
				world.config = world.config.copy(seed = Random.nextLong())
				Noise.setSeed(world.config.seed)
				worldManager.refreshWorldMesh(regenerateWorld = true)
			}
		}

		keyListeners += KeyboardInput.addKeyListener(KeyboardInput.KEY_ENTER, "Respawn") {
			if (it.isPressed) {
				player.respawn()
			}
		}

		keyListeners += KeyboardInput.addKeyListener(UniversalKeyCode('N'), "Noclip") {
			if (it.isPressed) {
				player.isNoclip = !player.isNoclip
			}
		}
	}

	fun update(dt: Float) {
		// Update look direction
		if (PointerInput.cursorMode == CursorMode.LOCKED) {
			val lookSensitivity = 0.15f
			player.yaw += PointerInput.primaryPointer.delta.x * lookSensitivity
			player.pitch += PointerInput.primaryPointer.delta.y * lookSensitivity
			player.pitch = player.pitch.coerceIn(-89.9f, 89.9f)
		}

		if (PointerInput.primaryPointer.isLeftButtonDown || PointerInput.primaryPointer.isRightButtonDown || PointerInput.primaryPointer.isMiddleButtonDown) {
			PointerInput.cursorMode = CursorMode.LOCKED
		}

		camera.position.set(
			player.position.x,
			player.position.y + 3.3f + player.cameraYOffset,
			player.position.z
		)

		val pitchRad = player.pitch.deg.rad
		val yawRad = player.yaw.deg.rad
		val lookDir = Vec3f(
			sin(yawRad) * cos(pitchRad),
			-sin(pitchRad),
			-cos(yawRad) * cos(pitchRad)
		)

		camera.lookAt.set(camera.position).add(lookDir)

		// Noclip speed adjustments
		val scroll = PointerInput.primaryPointer.scroll.y
		if (scroll != 0f) {
			player.noclipSpeed = (player.noclipSpeed + scroll * 2f).coerceIn(NOCLIP_MIN_SPEED, NOCLIP_MAX_SPEED)
		}
	}

	fun release() {
		keyListeners.forEach { KeyboardInput.removeKeyListener(it) }
		keyListeners.clear()
	}
}
