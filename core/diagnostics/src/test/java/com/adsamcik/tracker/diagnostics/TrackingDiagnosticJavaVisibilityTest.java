package com.adsamcik.tracker.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TrackingDiagnosticJavaVisibilityTest {
	@Test
	void recorderAndOperationScopeCannotBeImplementedOrConstructedFromJava() {
		Class<?> recorder = TrackingDiagnosticRecorder.class;
		Class<?> scope = Arrays.stream(recorder.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("OperationScope"))
			.findFirst()
			.orElseThrow();

		assertTrue(Modifier.isFinal(recorder.getModifiers()));
		assertTrue(Modifier.isFinal(scope.getModifiers()));
		assertTrue(TrackingDiagnosticEventRequest.class.isSealed());
		assertEquals(Object.class, recorder.getSuperclass());
		assertEquals(0, recorder.getInterfaces().length);
		assertNoAccessibleConstructor(recorder);
		assertNoAccessibleConstructor(scope);
		assertFalse(Arrays.stream(recorder.getDeclaredMethods()).anyMatch(method ->
			Modifier.isProtected(method.getModifiers()) || method.getName().contains("Sink")
		));
	}

	@Test
	void publicTrackingContractSignaturesContainNoTraceboxOrBackendTypes() {
		List<Class<?>> contractTypes = List.of(
			TrackingDiagnosticRecorder.class,
			TrackingDiagnosticEventRequest.class,
			TrackingDiagnosticEvents.class,
			TrackingDiagnosticMetricPolicy.class,
			TrackingDiagnosticPrivacyValidator.class
		);

		contractTypes.forEach(type -> {
			Arrays.stream(type.getMethods())
				.filter(method -> !method.isSynthetic())
				.forEach(this::assertPayloadFreeSignature);
			Arrays.stream(type.getFields()).forEach(this::assertPayloadFreeField);
		});
	}

	@Test
	void scopeExposesNeitherTokenNorPersistenceOrNetworkSurface() {
		Class<?> scope = Arrays.stream(TrackingDiagnosticRecorder.class.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("OperationScope"))
			.findFirst()
			.orElseThrow();
		String forbidden = "token|value|id|serialize|persist|store|network|upload|http|send|listener|observer";

		Arrays.stream(scope.getMethods())
			.filter(method -> method.getDeclaringClass() == scope && !method.isSynthetic())
			.forEach(method ->
				assertFalse(method.getName().toLowerCase().matches(".*(" + forbidden + ").*"))
			);
		Arrays.stream(scope.getFields()).forEach(field ->
			assertFalse(field.getName().toLowerCase().matches(".*(" + forbidden + ").*"))
		);
	}

	private void assertNoAccessibleConstructor(Class<?> type) {
		assertFalse(Arrays.stream(type.getDeclaredConstructors()).anyMatch(constructor ->
			!constructor.isSynthetic()
				&& (Modifier.isPublic(constructor.getModifiers())
					|| Modifier.isProtected(constructor.getModifiers()))
		));
	}

	private void assertPayloadFreeSignature(Method method) {
		String signature = method.toGenericString().toLowerCase();
		assertFalse(signature.contains("dev.tracebox"));
		assertFalse(signature.matches(".*(network|upload|http|listener|observer).*"));
	}

	private void assertPayloadFreeField(Field field) {
		String signature = field.toGenericString().toLowerCase();
		assertFalse(signature.contains("dev.tracebox"));
		assertFalse(signature.matches(".*(network|upload|http|listener|observer).*"));
	}
}
