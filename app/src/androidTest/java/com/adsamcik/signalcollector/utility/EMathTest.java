package com.adsamcik.signalcollector.utility;

import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.*;

public class EMathTest {
	@Test
	public void step() throws Exception {
		Assert.assertEquals(150, EMath.step(50, 90, 15));
		Assert.assertEquals(30, EMath.step(0, 28, 30));
	}

}