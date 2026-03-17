import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.addScene
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.LocalKeyCode
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.defaultOrbitCamera
import de.fabmax.kool.util.BackendScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

fun main() = KoolApplication(
	config = KoolConfigJvm()
) {
	// Initialize world with default configuration.
	val world = World(WorldConfig(width = 32, height = 10))
	world.generateAll()

	addScene {
		// Setup basic camera and background color.
		defaultOrbitCamera()
		clearColor = ClearColorFill(BACKGROUND)

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
		val keyListener = KeyboardInput.addKeyListener(LocalKeyCode('r'), "Regenerate") {
			if (it.isPressed) {
				world.config = world.config.copy(seed = Random.nextLong())
				world.generateAll()
				refreshWorldMesh()
			}
		}

		// Cleanup key listener when the scene is released.
		onRelease {
			KeyboardInput.removeKeyListener(keyListener)
		}
	}
}