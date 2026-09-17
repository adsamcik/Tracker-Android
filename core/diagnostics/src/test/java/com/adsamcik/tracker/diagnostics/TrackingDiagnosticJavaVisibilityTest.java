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
			TrackingDiagnosticPrivacyValidator.class,
			TrackerDiagnosticLog.class,
			TrackerDiagnosticInfoCode.class,
			TrackerDiagnosticWarningCode.class,
			TrackerDiagnosticFailureCode.class,
			TrackerDiagnosticRejectionCode.class
		);

		contractTypes.forEach(type -> {
			Arrays.stream(type.getMethods())
				.filter(method -> !method.isSynthetic())
				.forEach(this::assertPayloadFreeSignature);
			Arrays.stream(type.getFields()).forEach(this::assertPayloadFreeField);
		});
	}

	@Test
	void trackingFacadeAcceptsNoThrowableStringOrFreeformParameters() {
		Method[] methods = TrackerDiagnosticLog.class.getDeclaredMethods();
		Arrays.stream(methods)
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.flatMap(method -> Arrays.stream(method.getParameterTypes()))
			.forEach(parameterType -> {
				assertFalse(Throwable.class.isAssignableFrom(parameterType));
				assertFalse(CharSequence.class.isAssignableFrom(parameterType));
				assertFalse(parameterType.equals(Object.class));
				assertFalse(parameterType.getName().equals("java.io.File"));
				assertFalse(parameterType.getName().equals("java.net.URI"));
				assertFalse(parameterType.getName().equals("java.nio.file.Path"));
				assertFalse(java.util.Map.class.isAssignableFrom(parameterType));
			});

		Method failure = Arrays.stream(methods)
			.filter(method -> method.getName().equals("failure"))
			.findFirst()
			.orElseThrow();
		assertTrue(Arrays.equals(
			new Class<?>[] {
				TrackerDiagnosticFailureCode.class,
				TrackingDiagnosticFailureReason.class,
			},
			failure.getParameterTypes()
		));
	}

	@Test
	void rawBucketConvertersAreHiddenFromJavaCallers() {
		List<Class<?>> bucketTypes = List.of(
			TrackingDiagnosticCountBucket.class,
			TrackingDiagnosticDurationBucket.class,
			TrackingDiagnosticBacklogBucket.class,
			TrackingDiagnosticSizeBucket.class
		);

		bucketTypes.forEach(bucketType -> {
			Class<?> companion = Arrays.stream(bucketType.getDeclaredClasses())
				.filter(type -> type.getSimpleName().equals("Companion"))
				.findFirst()
				.orElseThrow();
			Arrays.stream(companion.getDeclaredMethods())
				.filter(method -> method.getName().startsWith("from"))
				.forEach(method -> assertTrue(method.isSynthetic()));
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
