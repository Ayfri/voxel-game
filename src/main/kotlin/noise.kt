import kotlin.math.abs
import kotlin.random.Random

/**
 * Simplex Noise implementation for generating coherent natural-looking terrain.
 */
data object Noise {
	private val permutation = IntArray(256)
	private val p = IntArray(512)
	private val pMod12 = IntArray(512)

	/**
	 * Sets the seed for noise generation. 
	 * This shuffles the permutation table to create unique worlds.
	 */
	fun setSeed(seed: Long) {
		val rand = Random(seed)
		val base = (0..255).toList().shuffled(rand)
		for (i in 0..255) {
			permutation[i] = base[i]
			p[i] = base[i]
			p[i + 256] = base[i]
			pMod12[i] = base[i] % 12
			pMod12[i + 256] = base[i] % 12
		}
	}

	init {
		// Default seed
		setSeed(0L)
	}

	private fun fastFloor(x: Double): Int {
		val xi = x.toInt()
		return if (x < xi) xi - 1 else xi
	}

	private val grad3x = intArrayOf(1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0)
	private val grad3y = intArrayOf(1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1)
	private val grad3z = intArrayOf(0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1)

	private fun dot(gIdx: Int, x: Double, y: Double, z: Double): Double =
		grad3x[gIdx] * x + grad3y[gIdx] * y + grad3z[gIdx] * z

	private const val F3 = 1.0 / 3.0
	private const val G3 = 1.0 / 6.0

	/**
	 * 3D Simplex noise implementation.
	 * Returns values in the range [-1.0, 1.0].
	 * Simplex noise is preferred over Perlin as it has fewer directional artifacts.
	 */
	fun noise(x: Double, y: Double = 0.0, z: Double = 0.0): Double {
		var n0: Double
		var n1: Double
		var n2: Double
		var n3: Double

		val s = (x + y + z) * F3
		val i = fastFloor(x + s)
		val j = fastFloor(y + s)
		val k = fastFloor(z + s)
		val t = (i + j + k) * G3
		val X0 = i - t
		val Y0 = j - t
		val Z0 = k - t
		val x0 = x - X0
		val y0 = y - Y0
		val z0 = z - Z0

		var i1: Int
		var j1: Int
		var k1: Int
		var i2: Int
		var j2: Int
		var k2: Int
		if (x0 >= y0) {
			if (y0 >= z0) {
				i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0
			} else if (x0 >= z0) {
				i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1
			} else {
				i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1
			}
		} else {
			if (y0 < z0) {
				i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1
			} else if (x0 < z0) {
				i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1
			} else {
				i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0
			}
		}

		val x1 = x0 - i1 + G3
		val y1 = y0 - j1 + G3
		val z1 = z0 - k1 + G3
		val x2 = x0 - i2 + 2.0 * G3
		val y2 = y0 - j2 + 2.0 * G3
		val z2 = z0 - k2 + 2.0 * G3
		val x3 = x0 - 1.0 + 3.0 * G3
		val y3 = y0 - 1.0 + 3.0 * G3
		val z3 = z0 - 1.0 + 3.0 * G3

		val ii = i and 255
		val jj = j and 255
		val kk = k and 255

		var t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0
		if (t0 < 0) n0 = 0.0
		else {
			t0 *= t0
			n0 = t0 * t0 * dot(pMod12[ii + p[jj + p[kk]]], x0, y0, z0)
		}

		var t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1
		if (t1 < 0) n1 = 0.0
		else {
			t1 *= t1
			n1 = t1 * t1 * dot(pMod12[ii + i1 + p[jj + j1 + p[kk + k1]]], x1, y1, z1)
		}

		var t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2
		if (t2 < 0) n2 = 0.0
		else {
			t2 *= t2
			n2 = t2 * t2 * dot(pMod12[ii + i2 + p[jj + j2 + p[kk + k2]]], x2, y2, z2)
		}

		var t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3
		if (t3 < 0) n3 = 0.0
		else {
			t3 *= t3
			n3 = t3 * t3 * dot(pMod12[ii + 1 + p[jj + 1 + p[kk + 1]]], x3, y3, z3)
		}

		return 32.0 * (n0 + n1 + n2 + n3)
	}

	/**
	 * Ridged fractal noise (sharp mountain peaks).
	 */
	fun ridged(
		x: Double,
		y: Double = 0.0,
		z: Double = 0.0,
		octaves: Int = 4,
		persistence: Double = 0.5,
		lacunarity: Double = 2.0
	): Double {
		var total = 0.0
		var frequency = 1.0
		var amplitude = 1.0
		var maxValue = 0.0
		for (i in 0 until octaves) {
			var v = noise(x * frequency, y * frequency, z * frequency)
			v = 1.0 - abs(v)
			v *= v // Sharpen peaks
			total += v * amplitude
			maxValue += amplitude
			amplitude *= persistence
			frequency *= lacunarity
		}
		return total / maxValue
	}

	/**
	 * Linear interpolation between two values.
	 */
	fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

	/**
	 * Generates fractal noise (Multiple octaves of Simplex noise).
	 * Superimposing multiple layers of noise at different scales creates 
	 * more realistic, rocky terrain.
	 */
	fun fractal(
		x: Double,
		y: Double = 0.0,
		z: Double = 0.0,
		octaves: Int = 4,
		persistence: Double = 0.5,
		lacunarity: Double = 2.0
	): Double {
		var total = 0.0
		var frequency = 1.0
		var amplitude = 1.0
		var maxValue = 0.0
		for (i in 0 until octaves) {
			total += noise(x * frequency, y * frequency, z * frequency) * amplitude
			maxValue += amplitude
			amplitude *= persistence
			frequency *= lacunarity
		}
		return total / maxValue
	}
}
