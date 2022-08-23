package com.github.throwable.beanref.lfp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import de.adito.picoservice.IPicoRegistration;
import de.adito.picoservice.PicoService;

public interface BeanRefCache {

	public static BeanRefCache instance() {
		return Static.instance();
	}

	<K, V> V get(K key, Function<K, V> loader);

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.TYPE })
	@PicoService
	public static @interface BeanRefCacheService {

		// alias for value
		String[] requiredClassNames() default {};

		int priority()

		default 0;

	}

	static enum Static {
		;

		private static final Object INSTANCE_MUTEX = new Object();
		private static BeanRefCache _INSTANCE;

		private static BeanRefCache instance() {
			if (_INSTANCE == null)
				synchronized (INSTANCE_MUTEX) {
					if (_INSTANCE == null)
						_INSTANCE = loadInstance();
				}
			return _INSTANCE;
		}

		private static BeanRefCache loadInstance() {
			var classLoaders = Stream.concat(Stream.of((ClassLoader) null), BeanRefUtils.streamDefaultClassLoaders())
					.distinct();
			var serviceProviers = classLoaders.flatMap(cl -> {
				var serviceLoader = cl == null ? ServiceLoader.load(IPicoRegistration.class)
						: ServiceLoader.load(IPicoRegistration.class, cl);
				return serviceLoader.stream();
			}).distinct();
			var registrationAnnos = serviceProviers.map(ServiceLoader.Provider::get).map(registration -> {
				var anno = registration.getAnnotatedClass().getAnnotation(BeanRefCacheService.class);
				if (anno == null)
					return null;
				return Map.entry(registration, anno);
			}).filter(Objects::nonNull);
			registrationAnnos = registrationAnnos.filter(ent -> {
				var registration = ent.getKey();
				if (!BeanRefCache.class.isAssignableFrom(ent.getKey().getAnnotatedClass()))
					return false;
				var anno = ent.getValue();
				return Stream.of(anno.requiredClassNames()).allMatch(v -> BeanRefUtils.classForName(v, true) != null);
			});
			registrationAnnos = registrationAnnos
					.sorted(Comparator.<Entry<IPicoRegistration, BeanRefCacheService>, Integer>comparing(ent -> {
						var anno = ent.getValue();
						return -1 * anno.priority();
					}));
			var beanRefCache = registrationAnnos.map(Entry::getKey).map(registration -> {
				var ct = registration.getAnnotatedClass();
				var enumConstants = ct.getEnumConstants();
				if (enumConstants != null && enumConstants.length == 1)
					return enumConstants[0];
				try {
					return ct.getConstructor().newInstance();
				} catch (RuntimeException e) {
					throw e;
				} catch (InstantiationException | IllegalAccessException | InvocationTargetException
						| NoSuchMethodException e) {
					throw new RuntimeException(e);
				}
			}).map(v -> (BeanRefCache) v).findFirst().orElse(null);
			if (beanRefCache != null)
				return beanRefCache;
			var cache = new WeakHashMap<Object, Object>();
			var concurrentCache = new ConcurrentHashMap<Object, Object>();
			return new BeanRefCache() {

				@SuppressWarnings("unchecked")
				@Override
				public <K, V> V get(K key, Function<K, V> loader) {
					Objects.requireNonNull(key);
					Objects.requireNonNull(loader);
					var value = cache.get(key);
					if (value == null) {
						Object[] resultRef = new Object[1];
						concurrentCache.compute(key, (nilk, nilv) -> {
							resultRef[0] = cache.computeIfAbsent(key, nil -> {
								return loader.apply(key);
							});
							return null;
						});
						value = resultRef[0];
					}
					return (V) value;
				}
			};
		}

	}

}
