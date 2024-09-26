package com.github.throwable.beanref;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.github.throwable.beanref.BeanPropertyResolver.SetterWriteAccessor;
import com.github.throwable.beanref.DynamicBeanPropertyResolver.GetterReadAccessor;

/**
 * BeanProperty represents a direct reference to a bean's property.
 * 
 * @param <BEAN> bean class
 * @param <TYPE> property's type
 */
public class BeanProperty<BEAN, TYPE> extends BeanPath<BEAN, TYPE> {
	private final Class<BEAN> beanClass;
	private final Class<TYPE> type;
	private final String name;
	private final Function<BEAN, TYPE> readAccessor;
	/* Nullable */
	private final BiConsumer<BEAN, TYPE> writeAccessor;
	/* Resolved lazily */
	private final Supplier</* Nullable */Supplier<TYPE>> instantiatorSupplier;

	BeanProperty(Class<BEAN> beanClass, Class<TYPE> type, String name, Function<BEAN, TYPE> readAccessor,
			/* Nullable */ BiConsumer<BEAN, TYPE> writeAccessor, Supplier<Supplier<TYPE>> instantiatorSupplier) {
		super();
		this.beanClass = beanClass;
		this.type = type;
		this.name = name;
		this.readAccessor = readAccessor;
		this.writeAccessor = writeAccessor;
		this.instantiatorSupplier = instantiatorSupplier;
	}

	@Override
	public Class<BEAN> getBeanClass() {
		return beanClass;
	}

	@Override
	public Class<TYPE> getType() {
		return type;
	}

	/**
	 * @return the name of bean's property
	 */
	public String getName() {
		return name;
	}

	@Override
	public String getPath() {
		return name;
	}

	/**
	 * @return read accessor to a property
	 */
	public Function<BEAN, TYPE> getReadAccessor() {
		return readAccessor;
	}

	public Optional<Method> tryGetReadMethod() {
		return Optional.ofNullable(getReadAccessor())
				.filter(GetterReadAccessor.class::isInstance)
				.map(GetterReadAccessor.class::cast)
				.map(GetterReadAccessor::getGetterMethod);
	}

	/**
	 * @return write accessor to a property (mutator) or null if property is
	 *         read-only
	 */
	public BiConsumer<BEAN, TYPE> getWriteAccessor() {
		return writeAccessor;
	}

	public Optional<Method> tryGetWriteMethod() {
		return Optional.ofNullable(getWriteAccessor())
				.filter(SetterWriteAccessor.class::isInstance)
				.map(SetterWriteAccessor.class::cast)
				.map(SetterWriteAccessor::getSetterMethod);
	}

	@Override
	public boolean isReadOnly() {
		return writeAccessor == null;
	}

	@Override
	public TYPE get(BEAN bean) {
		return readAccessor.apply(bean);
	}

	@Override
	public void set(BEAN bean, TYPE value) {
		if (isReadOnly())
			throw new ReadOnlyPropertyException("Property '" + toString() + "' is read-only");
		writeAccessor.accept(bean, value);
	}

	Supplier<TYPE> getInstantiator() {
		return instantiatorSupplier.get();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		BeanProperty<?, ?> that = (BeanProperty<?, ?>) o;
		return beanClass.equals(that.beanClass) && name.equals(that.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(beanClass, name);
	}
}
