package it.holoscreen.client;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Detects an active Iris shader pack without a compile-time dependency. With shaders, the custom GL drawing in the
 * middle of the world pass ends up in Iris' G-buffers, so the panel is drawn after the world instead.
 */
final class ShaderCompat {
	private static final Object IRIS_API = lookupIrisApi();
	private static final Method SHADER_PACK_IN_USE = lookupMethod();

	private ShaderCompat() {
	}

	static boolean shaderPackInUse() {
		if (IRIS_API == null || SHADER_PACK_IN_USE == null) {
			return false;
		}
		try {
			return (boolean) SHADER_PACK_IN_USE.invoke(IRIS_API);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return false;
		}
	}

	private static Object lookupIrisApi() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return null;
		}
		try {
			return Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return null;
		}
	}

	private static Method lookupMethod() {
		if (IRIS_API == null) {
			return null;
		}
		try {
			return IRIS_API.getClass().getMethod("isShaderPackInUse");
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}
}
