package com.github.throwable.beanref.lfp;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class BeanRefUtils {

	private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

	public static String hash(Serializable... serializables) {
		MessageDigest messageDigest = null;
		try {
			if (serializables != null)
				for (Serializable serializable : serializables) {
					if (serializable == null)
						continue;
					if (messageDigest == null)
						messageDigest = MessageDigest.getInstance("MD5");
					update(messageDigest, serializable);
				}
		} catch (NoSuchAlgorithmException | IOException e) {
			throw RuntimeException.class.isInstance(e) ? RuntimeException.class.cast(e) : new RuntimeException(e);
		}
		byte[] digest = messageDigest != null ? messageDigest.digest() : EMPTY_BYTE_ARRAY;
		return Base64.getEncoder().encodeToString(digest);
	}

	private static void update(MessageDigest messageDigest, Serializable serializable) throws IOException {
		try (OutputStream os = OutputStream.nullOutputStream();
				DigestOutputStream digestOutputStream = new DigestOutputStream(os, messageDigest);
				ObjectOutputStream oos = new ObjectOutputStream(digestOutputStream);) {
			oos.writeObject(serializable);
		}
	}

	private static boolean isNamedClassType(Class<?> classType) {
		if (classType == null)
			return false;
		if (classType.isAnonymousClass())
			return false;
		if (classType.isSynthetic())
			return false;
		if (Proxy.isProxyClass(classType))
			return false;
		return true;
	}

	public static <U> Class<U> getNamedClassType(Class<? extends U> classType) {
		Objects.requireNonNull(classType);
		if (isNamedClassType(classType))
			return (Class<U>) classType;
		var ifaces = classType.getInterfaces();
		if (ifaces.length == 0) {
			var superclass = classType.getSuperclass();
			if (superclass != null)
				return (Class<U>) getNamedClassType(superclass);
		} else if (ifaces.length == 1)
			return (Class<U>) getNamedClassType(ifaces[0]);
		// if proxy/system class this is the best we can do
		return (Class<U>) classType;
	}

	public static ClassLoader getDefaultClassLoader() {
		return streamDefaultClassLoaders().findFirst().get();
	}

	public static Stream<ClassLoader> streamDefaultClassLoaders() {
		Stream<Supplier<ClassLoader>> clSupplierStream = Stream.of(Thread.currentThread()::getContextClassLoader,
				BeanRefUtils.class::getClassLoader, ClassLoader::getSystemClassLoader,
				ClassLoader::getPlatformClassLoader);
		return clSupplierStream.map(Supplier::get).filter(Objects::nonNull).distinct();
	}

	public static Class<?> classForName(String className, boolean suppressErrors) {
		if (className == null || className.isEmpty()) {
			if (suppressErrors)
				return null;
			throw new IllegalArgumentException("className required");
		}
		RuntimeException error = null;
		Iterator<ClassLoader> classLoaderIter = BeanRefUtils.streamDefaultClassLoaders().iterator();
		while (classLoaderIter.hasNext()) {
			ClassLoader classLoader = classLoaderIter.next();
			try {
				var classType = Class.forName(className, false, classLoader);
				if (classType != null)
					return classType;
			} catch (Throwable t) {
				if (suppressErrors)
					continue;
				if (error == null)
					if (t instanceof RuntimeException)
						error = (RuntimeException) t;
					else
						error = new RuntimeException(t);
				else
					error.addSuppressed(t);
			}
		}
		if (suppressErrors)
			return null;
		throw error;
	}
}
