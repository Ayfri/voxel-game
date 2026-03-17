import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.addScene
import de.fabmax.kool.input.*
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.util.BackendScope
import de.fabmax.kool.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun main() = KoolApplication(
	config = KoolConfigJvm()
) {
	// Initialize world with default configuration.
	val world = World(WorldConfig(width = 32, height = 10))
	world.generateAll()

	addScene {
		// Initialize player
		val player = Player(world)

		// Setup basic camera and background color.
		camera = PerspectiveCamera()
		clearColor = ClearColorFill(BACKGROUND)

		onUpdate += {
			player.update(Time.deltaT)

			// Update look direction
			if (PointerInput.cursorMode == CursorMode.LOCKED) {
				val lookSensitivity = 0.15f
				player.yaw += PointerInput.primaryPointer.delta.x * lookSensitivity
				player.pitch += PointerInput.primaryPointer.delta.y * lookSensitivity
				player.pitch = player.pitch.coerceIn(-90f, 90f)
			}

			if (PointerInput.primaryPointer.isLeftButtonDown || PointerInput.primaryPointer.isRightButtonDown || PointerInput.primaryPointer.isMiddleButtonDown) {
				PointerInput.cursorMode = CursorMode.LOCKED
			}

			camera.position.set(
				player.position.x,
				player.position.y + 3.5f, // Head height
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
		}

		// Register player movement keys
		val keyListeners = Player.Controls.allMovement.flatMap {
			// Register both local and universal to be safe
			if (it.isLocal) listOf(it) else listOf(it, LocalKeyCode(it.code))
		}.map { keyCode ->
			KeyboardInput.addKeyListener(keyCode, "Move ${keyCode.name}") {
				player.onKeyEvent(it)
			}
		}

		val escListener = KeyboardInput.addKeyListener(KeyboardInput.KEY_ESC, "Unlock cursor") {
			if (it.isPressed) {
				PointerInput.cursorMode = CursorMode.NORMAL
			}
		}

		val worldNode = Node()
		addNode(worldNode)
		var voxelShader: KslShader? = null

		/**
		 * Clears existing world meshes and rebuilds them. 
		 * This is called on startup and whenever the seed changes.
		 */
		fun refreshWorldMesh() {
			worldNode.clearChildren()
			val shader = voxelShader ?: return
			worldNode.addVoxelWorld(world, shader)
		}

		// Asynchronous loading of textures and shader initialization.
		CoroutineScope(BackendScope.job).launch {
			val loadedTex = buildTextureArray(File("src/main/resources/blocks"))

			val shader = createVoxelShader().apply {
				texture3d("tBlockArray", loadedTex)
				uniform1f("uNumLayers", loadedTex.depth.toFloat())
			}
			voxelShader = shader

			// Initial mesh generation.
			refreshWorldMesh()
		}

		// Listen for 'R' key to regenerate the world with a new random seed.
		val keyListener = KeyboardInput.addKeyListener(UniversalKeyCode('R'), "Regenerate") {
			if (it.isPressed) {
				world.config = world.config.copy(seed = Random.nextLong())
				world.generateAll()
				refreshWorldMesh()
			}
		}

		// Listen for 'ENTER' key to respawn.
		val enterListener = KeyboardInput.addKeyListener(KeyboardInput.KEY_ENTER, "Respawn") {
			if (it.isPressed) {
				player.respawn()
			}
		}

		// Cleanup listeners when the scene is released.
		onRelease {
			KeyboardInput.removeKeyListener(keyListener)
			KeyboardInput.removeKeyListener(enterListener)
			KeyboardInput.removeKeyListener(escListener)
			keyListeners.forEach { KeyboardInput.removeKeyListener(it) }
		}
	}
}