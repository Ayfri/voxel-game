import de.fabmax.kool.*
import de.fabmax.kool.input.*
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.FilterMethod
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.util.*
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
	val world = World(WorldConfig(width = 96, height = 24))
	val cursorTexture = mutableStateOf<Texture2d?>(null)
	val fpsText = mutableStateOf("FPS: --")
	val noclipText = mutableStateOf("")
	val hudFont = mutableStateOf<Font?>(null)

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

			val bytes = File("src/main/resources/ui/cursor.png").readBytes()
			val buffer = Uint8BufferImpl(bytes)
			val image = Assets.loadImageFromBuffer(buffer, MimeType.forFileName("cursor.png"))
			cursorTexture.set(
				Texture2d(
					image,
					samplerSettings = SamplerSettings(
						minFilter = FilterMethod.NEAREST,
						magFilter = FilterMethod.NEAREST
					)
				)
			)

			val fontFile = File("src/main/resources/fonts/VCR_OSD_MONO_1.001.ttf")
			if (fontFile.exists()) {
				val baseFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, fontFile)
				java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(baseFont)
				hudFont.set(
					AtlasFont(
						family = baseFont.name,
						sizePts = 20f,
						ascentEm = 1.3f,
						descentEm = 0.5f,
						heightEm = 1.8f,
						samplerSettings = SamplerSettings(
							minFilter = FilterMethod.NEAREST,
							magFilter = FilterMethod.NEAREST
						)
					)
				)
			}

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

		val noclipListener = KeyboardInput.addKeyListener(UniversalKeyCode('N'), "Noclip") {
			if (it.isPressed) {
				player.isNoclip = !player.isNoclip
			}
		}

		onUpdate += {
			val scroll = PointerInput.primaryPointer.scroll.y
			if (scroll != 0f) {
				player.noclipSpeed = (player.noclipSpeed + scroll * 2f).coerceIn(1f, 400f)
			}
		}

		// Cleanup listeners and meshes when the scene is released.
		onRelease {
			KeyboardInput.removeKeyListener(keyListener)
			KeyboardInput.removeKeyListener(enterListener)
			KeyboardInput.removeKeyListener(noclipListener)
			KeyboardInput.removeKeyListener(escListener)
			keyListeners.forEach { KeyboardInput.removeKeyListener(it) }
			worldNode.children.forEach { if (it is Mesh<*>) it.release() }
		}

		onUpdate += {
			if (Time.frameCount % 20 == 0) {
				fpsText.set("FPS: ${Time.fps.toInt()}")
				if (player.isNoclip) {
					noclipText.set("NOCLIP (Speed: ${player.noclipSpeed.toInt()})")
				} else {
					noclipText.set("")
				}
			}
		}
	}

	addScene {
		setupUiScene()

		addNode(UiSurface(this, name = "HUD") {
			modifier
				.size(Grow.Std, Grow.Std)
				.background(null)

			renderHud(fpsText, noclipText, hudFont, cursorTexture)
		})
	}
}

private fun UiScope.renderHud(
	fpsText: MutableStateValue<String>,
	noclipText: MutableStateValue<String>,
	hudFont: MutableStateValue<Font?>,
	cursorTexture: MutableStateValue<Texture2d?>
) {
	Column {
		modifier
			.align(AlignmentX.Start, AlignmentY.Top)
			.margin(top = 8.dp, start = 8.dp)

		Text(fpsText.use()) {
			val font = hudFont.use() ?: sizes.smallText
			modifier
				.textColor(Color.WHITE)
				.font(font)
				.height(50.dp)
				.padding(horizontal = 8.dp)
				.textAlignX(AlignmentX.Start)
				.textAlignY(AlignmentY.Center)
		}

		val nt = noclipText.use()
		if (nt.isNotEmpty()) {
			Text(nt) {
				val font = hudFont.use() ?: sizes.smallText
				modifier
					.textColor(Color.RED)
					.font(font)
					.height(50.dp)
					.padding(horizontal = 8.dp)
					.textAlignX(AlignmentX.Start)
					.textAlignY(AlignmentY.Center)
			}
		}
	}

	val cursor = cursorTexture.use()
	if (cursor != null) {
		Image(cursor) {
			modifier
				.size(16.dp, 16.dp)
				.align(AlignmentX.Center, AlignmentY.Center)
		}
	}
}