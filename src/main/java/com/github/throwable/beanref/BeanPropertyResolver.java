package com.github.throwable.beanref;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.github.throwable.beanref.lfp.BeanRefCache;
import com.github.throwable.beanref.lfp.BeanRefUtils;

public class BeanPropertyResolver {

	protected BeanPropertyResolver() {}

	protected static <BEAN, TYPE> BeanProperty<BEAN, TYPE> resolveBeanProperty(
			MethodReferenceLambda<BEAN, TYPE> methodReferenceLambda) {
		Objects.requireNonNull(methodReferenceLambda);
		String hash = BeanRefUtils.hash(methodReferenceLambda);
		Object key = Arrays.asList(hash, "resolveBeanProperty");
		return BeanRefCache.instance().get(key, nil -> {
			return resolveBeanPropertyImpl(methodReferenceLambda);
		});
	}

	protected static <BEAN, TYPE> BeanProperty<BEAN, TYPE> resolveCollectionBeanProperty(
			MethodReferenceLambda<BEAN, Collection<TYPE>> methodReferenceLambda,
			/* Nullable */ Supplier<Collection<TYPE>> collectionInstantiator) {
		Objects.requireNonNull(methodReferenceLambda);
		String hash = BeanRefUtils.hash(methodReferenceLambda,
				Optional.ofNullable(collectionInstantiator)
						.filter(Serializable.class::isInstance)
						.map(Serializable.class::cast)
						.orElse(null));
		Object key = Arrays.asList(hash, "resolveCollectionBeanProperty");
		return BeanRefCache.instance().get(key, nil -> {
			return resolveCollectionBeanPropertyImpl(methodReferenceLambda, collectionInstantiator);
		});
	}

	protected static <BEAN, TYPE> BeanProperty<BEAN, TYPE> resolveBeanPropertyImpl(
			MethodReferenceLambda<BEAN, TYPE> methodReferenceLambda) {
		SerializedLambda serialized = serialized(methodReferenceLambda);
		final Class<BEAN> beanClass = getContainingClass(serialized);
		final Method getterMethod = findGetterMethod(beanClass, serialized.getImplMethodName());
		@SuppressWarnings("unchecked")
		final Class<TYPE> type = (Class<TYPE>) getterMethod.getReturnType();
		final String propertyName = resolvePropertyName(getterMethod.getName());
		final Method setterMethod = findSetterMethod(beanClass, propertyName, type, getterMethod.getName());
		final BiConsumer<BEAN, TYPE> writeAccessor = setterMethod != null ? new SetterWriteAccessor<>(setterMethod)
				: null;
		return new BeanProperty<>(beanClass, type, propertyName, methodReferenceLambda, writeAccessor,
				new InstantiatorResolver<>(type));
	}

	protected static <BEAN, TYPE> BeanProperty<BEAN, TYPE> resolveCollectionBeanPropertyImpl(
			MethodReferenceLambda<BEAN, Collection<TYPE>> methodReferenceLambda,
			/* Nullable */ Supplier<Collection<TYPE>> collectionInstantiator) {
		SerializedLambda serialized = serialized(methodReferenceLambda);
		final Class<BEAN> beanClass = getContainingClass(serialized);
		final Method getterMethod = findGetterMethod(beanClass, serialized.getImplMethodName());
		@SuppressWarnings("unchecked")
		final Class<Collection<TYPE>> type = (Class<Collection<TYPE>>) getterMethod.getReturnType();
		final ParameterizedType genericReturnType = (ParameterizedType) getterMethod.getGenericReturnType();
		if (genericReturnType.getActualTypeArguments().length != 1)
			throw new IllegalArgumentException(
					"Can not determine parameter type for " + beanClass.getName() + "." + getterMethod.getName());
		@SuppressWarnings("unchecked")
		final Class<TYPE> elementType = (Class<TYPE>) genericReturnType.getActualTypeArguments()[0];

		final String propertyName = resolvePropertyName(getterMethod.getName());
		final Method setterMethod = findSetterMethod(beanClass, propertyName, type, getterMethod.getName());
		final BiConsumer<BEAN, Collection<TYPE>> writeAccessor = setterMethod != null
				? new SetterWriteAccessor<>(setterMethod)
				: null;
		final CollectionElementReadAccessor<BEAN, TYPE> beantypeCollectionElementReadAccessor = new CollectionElementReadAccessor<>(
				methodReferenceLambda);
		final Supplier<Supplier<Collection<TYPE>>> collectionInstantiatorResolver;
		if (collectionInstantiator == null && writeAccessor != null)
			collectionInstantiatorResolver = defaultCollectionInstantiatorResolver(type);
		else
			collectionInstantiatorResolver = () -> collectionInstantiator;
		final CollectionElementWriteAccessor<BEAN, TYPE> beantypeCollectionElementWriteAccessor = new CollectionElementWriteAccessor<>(
				methodReferenceLambda, writeAccessor, collectionInstantiatorResolver);
		return new BeanProperty<>(beanClass, elementType, propertyName, beantypeCollectionElementReadAccessor,
				beantypeCollectionElementWriteAccessor, new InstantiatorResolver<>(elementType));
	}

	protected static <BEAN> Method findGetterMethod(Class<BEAN> beanClass, String getterMethodName) {
		final Method getterMethod;
		try {
			getterMethod = beanClass.getMethod(getterMethodName);
		} catch (NoSuchMethodException e) {
			throw new IllegalBeanPathException(e);
		}

		if (getterMethod.getParameterCount() > 0)
			throw new IllegalBeanPathException("Illegal getter method: " + getterMethodName);
		if (Void.TYPE.equals(getterMethod.getReturnType()))
			throw new IllegalBeanPathException("Illegal getter method return type: " + getterMethodName + "("
					+ getterMethod.getReturnType() + ")");
		return getterMethod;
	}

	protected static String resolvePropertyName(String getterMethodName) {
		final String propertyName;

		if (getterMethodName.startsWith("get") && getterMethodName.length() > 3
				&& Character.isUpperCase(getterMethodName.charAt(3))) {
			propertyName = Character.toLowerCase(getterMethodName.charAt(3)) + getterMethodName.substring(4);
		} else if (getterMethodName.startsWith("is") && getterMethodName.length() > 2
				&& Character.isUpperCase(getterMethodName.charAt(2))) {
			propertyName = Character.toLowerCase(getterMethodName.charAt(2)) + getterMethodName.substring(3);
		} else {
			// non-canonical name: use method name as-is
			propertyName = getterMethodName;
		}
		return propertyName;
	}

	protected static <BEAN, TYPE> /* Nullable */ Method findSetterMethod(Class<BEAN> beanClass, String propertyName,
			Class<TYPE> type, String getterMethodName) {
		if (!getterMethodName.equals(propertyName)) {
			// canonical getXXX(): search for setXXX()
			final String setterMethodName = "set" + Character.toUpperCase(propertyName.charAt(0))
					+ propertyName.substring(1);
			try {
				return beanClass.getMethod(setterMethodName, type);
			} catch (NoSuchMethodException e) {
				// Read-only
				return null;
			}
		} else {
			// non-canonical xxx(): search for xxx(value)
			try {
				return beanClass.getMethod(propertyName, type);
			} catch (NoSuchMethodException e) {
				// Read-only
				return null;
			}
		}
	}

	protected static SerializedLambda serialized(Object lambda) {
		SerializedLambda serializedLambda;
		try {
			Method writeMethod = lambda.getClass().getDeclaredMethod("writeReplace");
			writeMethod.setAccessible(true);
			serializedLambda = (SerializedLambda) writeMethod.invoke(lambda);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		if (serializedLambda.getImplMethodName().startsWith("lambda$"))
			throw new IllegalArgumentException("Not a method reference");
		return serializedLambda;
	}

	protected static String getContainingClassName(SerializedLambda lambda) {
		return lambda.getImplClass();
	}

	@SuppressWarnings("unchecked")
	protected static <T> Class<T> getContainingClass(SerializedLambda lambda) {
		String className = getContainingClassName(lambda).replaceAll("/", ".");
		return (Class<T>) BeanRefUtils.classForName(className, false);
	}

	public static class SetterWriteAccessor<BEAN, TYPE> implements BiConsumer<BEAN, TYPE> {
		private final Method setterMethod;

		public SetterWriteAccessor(Method setterMethod) {
			this.setterMethod = setterMethod;
		}

		@Override
		public void accept(BEAN bean, TYPE value) {
			try {
				setterMethod.invoke(bean, value);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				if (e.getTargetException() instanceof RuntimeException)
					// unchecked: throw as is
					throw (RuntimeException) e.getTargetException();
				else
					// checked: wrap into runtime
					throw new RuntimeException(e.getTargetException());
			}
		}

		/**
		 * @return the setterMethod
		 */
		public Method getSetterMethod() {
			return setterMethod;
		}
	}

	public static class InstantiatorResolver<TYPE> implements Supplier<Supplier<TYPE>> {
		private final Class<TYPE> type;
		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		private volatile Optional<Supplier<TYPE>> resolvedInstantiator;

		public InstantiatorResolver(Class<TYPE> type) {
			this.type = type;
		}

		@Override
		public Supplier<TYPE> get() {
			// noinspection OptionalAssignedToNull
			if (resolvedInstantiator == null) {
				resolvedInstantiator = Optional.ofNullable(resolveInstantiator(type));
			}
			return resolvedInstantiator.orElse(null);
		}

		private static <TYPE> Supplier<TYPE> resolveInstantiator(Class<TYPE> type) {
			if ((type.getModifiers() & Modifier.ABSTRACT) != 0)
				return null;
			final Constructor<TYPE> constructor;
			try {
				constructor = type.getConstructor();
			} catch (NoSuchMethodException e) {
				return null;
			}
			return () -> {
				try {
					return constructor.newInstance();
				} catch (InstantiationException | IllegalAccessException e) {
					throw new RuntimeException(e);
				} catch (InvocationTargetException e) {
					if (e.getTargetException() instanceof RuntimeException)
						throw (RuntimeException) e.getTargetException();
					else
						throw new RuntimeException(e.getTargetException());
				}
			};
		}
	}

	protected static <TYPE> Supplier<Supplier<Collection<TYPE>>> defaultCollectionInstantiatorResolver(
			Class<Collection<TYPE>> collectionType) {
		if ((collectionType.getModifiers() & Modifier.ABSTRACT) != 0) {
			if (collectionType.isAssignableFrom(List.class))
				return () -> ArrayList::new;
			else if (collectionType.isAssignableFrom(Set.class))
				return () -> LinkedHashSet::new;
			else
				return null;
		} else {
			// instantiatable
			return new InstantiatorResolver<>(collectionType);
		}
	}

	public static class CollectionElementReadAccessor<BEAN, TYPE> implements Function<BEAN, TYPE> {
		private final Function<BEAN, Collection<TYPE>> beanPropertyReadAccessor;

		public CollectionElementReadAccessor(Function<BEAN, Collection<TYPE>> beanPropertyReadAccessor) {
			this.beanPropertyReadAccessor = beanPropertyReadAccessor;
		}

		@Override
		public TYPE apply(BEAN bean) {
			final Collection<TYPE> collection = beanPropertyReadAccessor.apply(bean);
			if (collection == null || collection.isEmpty())
				return null;
			if (collection instanceof List) {
				final List<TYPE> list = (List<TYPE>) collection;
				return list.get(list.size() - 1);
			} else {
				// Always obtain last element
				final Iterator<TYPE> it = collection.iterator();
				TYPE elem = it.next();
				while (it.hasNext())
					elem = it.next();
				return elem;
			}
		}
	}

	public static class CollectionElementWriteAccessor<BEAN, TYPE> implements BiConsumer<BEAN, TYPE> {
		private final Function<BEAN, Collection<TYPE>> beanPropertyReadAccessor;
		/* Nullable */
		private final BiConsumer<BEAN, Collection<TYPE>> beanPropertyWriteAccessor;
		/* Nullable */
		private final Supplier<Supplier<Collection<TYPE>>> collectionInstantiatorResolver;

		public CollectionElementWriteAccessor(Function<BEAN, Collection<TYPE>> beanPropertyReadAccessor,
				/* Nullable */BiConsumer<BEAN, Collection<TYPE>> beanPropertyWriteAccessor,
				Supplier<Supplier<Collection<TYPE>>> collectionInstantiatorResolver) {
			this.beanPropertyReadAccessor = beanPropertyReadAccessor;
			this.beanPropertyWriteAccessor = beanPropertyWriteAccessor;
			this.collectionInstantiatorResolver = collectionInstantiatorResolver;
		}

		@Override
		public void accept(BEAN bean, TYPE value) {
			Collection<TYPE> collection = beanPropertyReadAccessor.apply(bean);
			if (collection == null) {
				if (beanPropertyWriteAccessor == null)
					throw new IncompletePathException(
							new ReadOnlyPropertyException("Collection property is read-only"));
				final Supplier<Collection<TYPE>> collectionInstantiator = collectionInstantiatorResolver.get();
				if (collectionInstantiator == null)
					throw new IncompletePathException("Cannot instantiate new collection due to unknown type");
				collection = collectionInstantiator.get();
				try {
					beanPropertyWriteAccessor.accept(bean, collection);
				} catch (Exception e) {
					throw new IncompletePathException(e);
				}
			}
			if (value == null)
				collection.clear();
			else
				collection.add(value);
		}
	}

}
