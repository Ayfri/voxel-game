import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.addScene
import de.fabmax.kool.input.*
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.util.BackendScope
import de.fabmax.kool.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun main() = KoolApplication(
	config = KoolConfigJvm()
) {
	// Initialize world with default configuration.
	val world = World(WorldConfig(width = 32, height = 10))

	addScene {
		// Initialize player
		val player = Player(world)

		// Setup basic camera and background color.
		camera = PerspectiveCamera()
		clearColor = ClearColorFill(BACKGROUND)

		val worldNode = Node()
		addNode(worldNode)
		val voxelShader = AtomicReference<KslShader?>(null)

		var needsRefresh = true
		val isRefreshing = AtomicBoolean(false)
		val worldIsGenerated = AtomicBoolean(false)
		val pendingMeshes = AtomicReference<List<Mesh<*>>?>(null)

		/**
		 * Clears existing world meshes and rebuilds them. 
		 * This is called on startup and whenever the seed changes.
		 */
		fun refreshWorldMesh(regenerateWorld: Boolean = false) {
			if (isRefreshing.get()) return
			val shader = voxelShader.get() ?: return

			isRefreshing.set(true)
			player.physicsEnabled = false
			CoroutineScope(BackendScope.job).launch {
				if (regenerateWorld || !worldIsGenerated.get()) {
					world.generateAll()
					worldIsGenerated.set(true)
				}
				pendingMeshes.set(generateWorldMeshes(world, shader, player.position.x, player.position.z))
			}
		}

		onUpdate += {
			pendingMeshes.getAndSet(null)?.let { meshes ->
				worldNode.children.forEach { if (it is Mesh<*>) it.release() }
				worldNode.clearChildren()
				meshes.forEach { worldNode.addNode(it) }

				player.respawn()
				player.physicsEnabled = true
				isRefreshing.set(false)
			}

			if (needsRefresh && voxelShader.get() != null) {
				needsRefresh = false
				refreshWorldMesh()
			}
			player.update(Time.deltaT)

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
				player.position.y + 3.3f, // Head height
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

		CoroutineScope(BackendScope.job).launch {
			val loadedTex = buildTextureArray(File("src/main/resources/blocks"))

			val shader = createVoxelShader().apply {
				texture3d("tBlockArray", loadedTex)
				uniform1f("uNumLayers", loadedTex.depth.toFloat())
			}
			voxelShader.set(shader)

			// Initial mesh generation.
			refreshWorldMesh()
		}

		// Listen for 'R' key to regenerate the world with a new random seed.
		val keyListener = KeyboardInput.addKeyListener(UniversalKeyCode('R'), "Regenerate") {
			if (it.isPressed && !isRefreshing.get()) {
				world.config = world.config.copy(seed = Random.nextLong())
				refreshWorldMesh(regenerateWorld = true)
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