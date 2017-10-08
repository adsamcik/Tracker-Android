package com.adsamcik.signalcollector.utility;

import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.*;

public class EMathTest {
	@Test
	public void step() throws Exception {
		Assert.assertEquals(30, EMath.step(0, 30, 30));
		Assert.assertEquals(30, EMath.step(0, 1, 30));
		Assert.assertEquals(60, EMath.step(90, -1, 30));
		Assert.assertEquals(150, EMath.step(149, 1, 30));

		//negative value test
		Assert.assertEquals(-60, EMath.step(-90, 1, 30));
		Assert.assertEquals(-60, EMath.step(-30, -1, 30));

		//0 direction test
		Assert.assertEquals(-30, EMath.step(-30, 0, 30));
		Assert.assertEquals(30, EMath.step(30, 0, 30));
	}

}