package com.github.throwable.beanref;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.throwable.beanref.lfp.BeanRefUtils;

public class BeanRefTestLFP {

	public static void main(String[] args) throws IOException {
		System.out.println(Stream.of(Future.class, Future.class, Runnable.class)
				.collect(Collectors.toCollection(LinkedHashSet::new))
				.equals(new HashSet<Class<?>>(Arrays.asList(Runnable.class, Future.class))));

		Date date0 = new Date();
		Date date1 = new Date() {};
		System.out.println(BeanRef.$(date0.getClass()).all().size());
		System.out.println(BeanRef.$(date1.getClass()).all().size());
		System.out.println(BeanRef.$(Date::getTime).getPath());
		System.out.println(BeanRef.$(Date::getTime).getPath());
		MethodReferenceLambda<Date, Long> mrl0 = Date::getTime;
		MethodReferenceLambda<Date, Long> mrl1 = Date::getTime;
		System.out.println(BeanRefUtils.hash(mrl0).equals(BeanRefUtils.hash(mrl1)));
		System.out.println(BeanRefUtils.hash(mrl0));
		System.out.println(BeanRefUtils.hash(mrl1));
		System.out.println(BeanRefUtils.hash((MethodReferenceLambda<Date, ? extends Object>) Date::getDay));
	}

}
