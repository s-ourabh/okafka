package org.oracle.okafka.tests;

import java.util.Properties;
import org.junit.BeforeClass;

public class OkafkaSetup {
	
    @BeforeClass
    public static Properties setup(){
	  return OkafkaTestSupport.baseProperties();
    }
}
