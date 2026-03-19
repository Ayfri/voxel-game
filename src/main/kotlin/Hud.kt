import de.fabmax.kool.Assets
import de.fabmax.kool.MimeType
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.FilterMethod
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.util.*
import java.io.File

class Hud(val player: Player, val worldNode: Node, val camera: PerspectiveCamera) {
	val fpsText = mutableStateOf("FPS: --")
	val meshesText = mutableStateOf("Meshes: --")
	val noclipText = mutableStateOf("")
	val posText = mutableStateOf("Pos: --")
	val hudFont = mutableStateOf<Font?>(null)
	val cursorTexture = mutableStateOf<Texture2d?>(null)

	var font: Font?
		get() = hudFont.value
		set(value) {
			hudFont.value = value
		}

	fun update(dt: Float) {
		if (Time.frameCount % 20 == 0) {
			val totalMeshes = worldNode.children.filterIsInstance<Mesh<*>>().size
			val visibleMeshes = worldNode.children.filterIsInstance<Mesh<*>>().count {
				camera.isInFrustum(it)
			}
			fpsText.set("FPS: ${Time.fps.toInt()}")
			meshesText.set("Meshes: $visibleMeshes / $totalMeshes")
			if (player.isNoclip) {
				noclipText.set("NOCLIP (Speed: ${player.noclipSpeed.toInt()})")
			} else {
				noclipText.set("")
			}

			val p = player.position
			posText.set("X: ${p.x.format(1)}, Y: ${p.y.format(1)}, Z: ${p.z.format(1)}")
		}
	}

	private fun Float.format(digits: Int): String = String.format("%.${digits}f", this)

	suspend fun loadResources() {
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
	}

	fun UiScope.renderText(mutableState: String, modifiers: TextModifier.() -> Unit = {}) {
		val font = hudFont.use() ?: sizes.smallText
		Text(mutableState) {
			modifier
				.textColor(Color.WHITE)
				.font(font)
				.height(50.dp)
				.padding(start = 8.dp)
				.textAlignX(AlignmentX.Start)
				.textAlignY(AlignmentY.Center)
				.modifiers()
		}
	}

	fun renderHud(uiScope: UiScope) {
		with(uiScope) {
			Column {
				modifier
					.align(AlignmentX.Start, AlignmentY.Top)
					.margin(top = 8.dp, start = 8.dp)

				renderText(fpsText.use())
				renderText(posText.use())
				renderText(meshesText.use())

				val nt = noclipText.use()
				if (nt.isNotEmpty()) {
					renderText(nt) {
						textColor(Color.RED)
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
	}
}
