package io.rsocket.ipc.reflection.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.opentracing.Tracer;
import io.rsocket.ipc.Marshaller;
import io.rsocket.ipc.MetadataDecoder;
import io.rsocket.ipc.RequestHandlingRSocket;
import io.rsocket.ipc.Server;
import io.rsocket.ipc.Server.H;
import io.rsocket.ipc.Unmarshaller;
import io.rsocket.ipc.marshallers.Bytes;
import io.rsocket.ipc.reflection.core.MethodMapUtils;
import io.rsocket.ipc.reflection.core.PublisherConverter;
import io.rsocket.ipc.reflection.core.PublisherConverters;
import io.rsocket.ipc.util.TriFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RequestHandlingRSocketReflection extends RequestHandlingRSocket {
	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final Logger logger = java.util.logging.Logger.getLogger(THIS_CLASS.getName());

	public RequestHandlingRSocketReflection() {
		super();
	}

	public RequestHandlingRSocketReflection(MetadataDecoder decoder) {
		super(decoder);
	}

	public RequestHandlingRSocketReflection(Tracer tracer) {
		super(tracer);
	}

	public <S> void register(Class<S> serviceType, S service, Marshaller<Object> resultMarshaller,
			Unmarshaller<Object[]> unmarshaller) {
		register(serviceType, service, resultMarshaller, (paramTypes, bb, md) -> unmarshaller.apply(bb));
	}

	public <S> void register(Class<S> serviceType, S service, Marshaller<Object> resultMarshaller,
			Function<Type[], Unmarshaller<Object[]>> argumentDeserializer) {
		register(serviceType, service, resultMarshaller,
				(paramTypes, bb, md) -> argumentDeserializer.apply(paramTypes).apply(bb));
	}

	public <S> void register(Class<S> serviceType, S service, Marshaller<Object> resultMarshaller,
			TriFunction<Type[], ByteBuf, ByteBuf, Object[]> argumentDeserializer) {
		Objects.requireNonNull(serviceType);
		Objects.requireNonNull(service);
		Objects.requireNonNull(resultMarshaller);
		Objects.requireNonNull(argumentDeserializer);
		Map<String, Method> methods = MethodMapUtils.getMappedMethods(serviceType, true);
		Set<String> serviceNameTracker = new HashSet<>();
		for (boolean lowercase : Arrays.asList(false, true)) {
			for (boolean simpleName : Arrays.asList(false, true)) {
				String serviceName = simpleName ? serviceType.getSimpleName() : serviceType.getName();
				serviceName = lowercase ? serviceName.toLowerCase() : serviceName;
				if (!serviceNameTracker.add(serviceName))
					continue;
				register(service, serviceName, resultMarshaller, argumentDeserializer, methods);
			}
		}
	}

	private <S> void register(S service, String serviceName, Marshaller<Object> resultMarshaller,
			TriFunction<Type[], ByteBuf, ByteBuf, Object[]> argumentDeserializer, Map<String, Method> methodMapping) {
		H<Object, ByteBuf> serviceBuilder = Server.service(serviceName).noMeterRegistry().noTracer()
				.marshall(resultMarshaller).unmarshall(Bytes.byteBufUnmarshaller());
		methodMapping.entrySet().forEach(ent -> {
			String route = ent.getKey();
			logger.log(Level.FINE,
					String.format("registering request handler. serviceName:%s route:%s", serviceName, route));
			register(service, route, argumentDeserializer, ent.getValue(), serviceBuilder);
		});
		this.withEndpoint(serviceBuilder.toIPCRSocket());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static <S> void register(S service, String route,
			TriFunction<Type[], ByteBuf, ByteBuf, Object[]> argumentDeserializer, Method method,
			H<Object, ByteBuf> serviceBuilder) {
		if (registerRequestChannel(service, route, argumentDeserializer, method, serviceBuilder))
			return;
		if (method.getReturnType().equals(Void.TYPE)) {
			serviceBuilder.fireAndForget(route, (data, md) -> {
				invoke(service, method, argumentDeserializer.apply(method.getGenericParameterTypes(), data, md));
				return Mono.empty();
			});
			return;
		}
		Optional<PublisherConverter> returnPublisherConverter = PublisherConverters.lookup(method.getReturnType())
				.map(v -> v);
		if (returnPublisherConverter.isPresent() && !Mono.class.isAssignableFrom(method.getReturnType())) {
			serviceBuilder.requestStream(route, (data, md) -> {
				Object result = invoke(service, method,
						argumentDeserializer.apply(method.getGenericParameterTypes(), data, md));
				return Flux.from(returnPublisherConverter.get().toPublisher(result));
			});
			return;
		}
		serviceBuilder.requestResponse(route, (data, md) -> {
			Object result = invoke(service, method,
					argumentDeserializer.apply(method.getGenericParameterTypes(), data, md));
			if (returnPublisherConverter.isPresent())
				return Mono.from(returnPublisherConverter.get().toPublisher(result));
			return Mono.just(result);
		});
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static <S> boolean registerRequestChannel(S service, String route,
			TriFunction<Type[], ByteBuf, ByteBuf, Object[]> argumentDeserializer, Method method,
			H<Object, ByteBuf> serviceBuilder) {
		Optional<Type> requestChannelParameterType = MethodMapUtils.getRequestChannelParameterType(method);
		if (!requestChannelParameterType.isPresent())
			return false;
		PublisherConverter returnPublisherConverter = PublisherConverters.lookup(method.getReturnType()).get();
		Type[] typeArguments = new Type[] { requestChannelParameterType.get() };
		serviceBuilder.requestChannel(route, (first, publisher, md) -> {
			Flux<Object> deserializedPublisher = Flux.from(publisher).map(bb -> {
				Object[] payload = argumentDeserializer.apply(typeArguments, bb, md);
				return payload[0];
			});
			Object result = invoke(service, method, new Object[] { deserializedPublisher });
			return Flux.from(returnPublisherConverter.toPublisher(result));
		});
		return true;
	}

	private static <S> Object invoke(S serivce, Method method, Object[] arguments) {
		try {
			return method.invoke(serivce, arguments);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw java.lang.RuntimeException.class.isAssignableFrom(e.getClass())
					? java.lang.RuntimeException.class.cast(e)
					: new java.lang.RuntimeException(e);
		}
	}

}