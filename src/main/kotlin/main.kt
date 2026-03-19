import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.addScene
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.util.BackendScope
import de.fabmax.kool.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

fun main() = KoolApplication(
	config = KoolConfigJvm()
) {
	val world = World(WorldConfig(renderDistance = 32))
	Noise.setSeed(world.config.seed)

	val camera = PerspectiveCamera()
	val worldNode = Node()
	val player = Player(world)
	val voxelShader = AtomicReference<KslShader?>(null)
	val worldManager = WorldManager(world, player, worldNode, voxelShader)

	val hud = Hud(player, worldNode, camera)
	val controls = PlayerControls(player, camera, world, worldManager)

	addScene {
		this.camera = camera
		clearColor = ClearColorFill(BACKGROUND)

		addNode(worldNode)

		onUpdate += {
			worldManager.update(Time.deltaT)
			player.update(Time.deltaT)
			controls.update(Time.deltaT)
			hud.update(Time.deltaT)
		}

		CoroutineScope(BackendScope.job).launch {
			val loadedTex = buildTextureArray(File("src/main/resources/blocks"))

			val shader = createVoxelShader().apply {
				texture3d("tBlockArray", loadedTex)
				uniform1f("uNumLayers", loadedTex.depth.toFloat())
			}
			voxelShader.set(shader)

			hud.loadResources()

			// Initial mesh generation.
			worldManager.refreshWorldMesh()
		}

		// Cleanup listeners and meshes when the scene is released.
		onRelease {
			controls.release()
			worldNode.children.forEach { if (it is Mesh<*>) it.release() }
		}
	}

	addScene {
		setupUiScene()

		addNode(UiSurface(this, name = "HUD") {
			modifier
				.size(Grow.Std, Grow.Std)
				.background(null)

			hud.renderHud(this)
		})
	}
}
