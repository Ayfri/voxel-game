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

	private var raycastResult: RaycastResult? = null

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

		// Block selection
		keyListeners += KeyboardInput.addKeyListener(UniversalKeyCode('1'), "Select Grass") {
			if (it.isPressed) player.selectedBlockId = 0
		}
		keyListeners += KeyboardInput.addKeyListener(UniversalKeyCode('2'), "Select Dirt") {
			if (it.isPressed) player.selectedBlockId = 1
		}
		keyListeners += KeyboardInput.addKeyListener(UniversalKeyCode('3'), "Select Stone") {
			if (it.isPressed) player.selectedBlockId = 2
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

		val isLmbClicked = PointerInput.primaryPointer.isLeftButtonClicked
		val isRmbClicked = PointerInput.primaryPointer.isRightButtonClicked

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

		// Raycast for block interaction
		raycastResult = raycast(world, camera.position, lookDir, 10f)

		if (PointerInput.cursorMode == CursorMode.LOCKED) {
			if (isLmbClicked) {
				raycastResult?.let {
					worldManager.setBlock(it.blockPos.x, it.blockPos.y, it.blockPos.z, -1)
				}
			} else if (isRmbClicked) {
				raycastResult?.let {
					val placePos = it.blockPos + it.face
					if (!player.intersectsBlock(placePos.x, placePos.y, placePos.z)) {
						worldManager.setBlock(placePos.x, placePos.y, placePos.z, player.selectedBlockId)
					}
				}
			}
		}

		// Update preview
		worldManager.updatePreview(raycastResult, player.selectedBlockId)

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
