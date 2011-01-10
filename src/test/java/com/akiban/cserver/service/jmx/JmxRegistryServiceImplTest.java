package com.akiban.cserver.service.jmx;

import static junit.framework.Assert.assertEquals;

import java.util.HashSet;
import java.util.Set;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.junit.Test;

public final class JmxRegistryServiceImplTest {
    private interface TestMXBean {
        public int getInt();
    }

    private interface NotMXBeanInterface {
        public int getInt();
    }

    private interface SubInterface extends TestMXBean {

    }

    private static class GoodService implements TestMXBean, JmxManageable {
        private final String name;

        private GoodService(String name) {
            this.name = name;
        }

        @Override
        public int getInt() {
            return 2;
        }

        @Override
        public JmxObjectInfo getJmxObjectInfo() {
            return new JmxObjectInfo(name, this, TestMXBean.class);
        }
    }

    private static class MockedJmxRegistry extends JmxRegistryServiceImpl {
        final MBeanServerProxy.MockMBeanServer mock = MBeanServerProxy.getMock();
        @Override
        protected MBeanServerProxy.MockMBeanServer getMBeanServer() {
            return mock;
        }
    }

    @Test(expected=JmxRegistrationException.class)
    public void managedClassNotAnInterface() {
        class MyMXBean implements JmxManageable {
            public int getInt() { return 1; }

            @Override
            public JmxObjectInfo getJmxObjectInfo() {
                return new JmxObjectInfo("Test", this, MyMXBean.class);
            }
        }

        MockedJmxRegistry service = new MockedJmxRegistry();
        final MyMXBean probe = new MyMXBean();
        service.validate(probe.getJmxObjectInfo());
    }

    @Test(expected=JmxRegistrationException.class)
    public void managedInterfaceNotWellNamed() {
        class MyMXBean implements JmxManageable, NotMXBeanInterface {
            public int getInt() { return 1; }

            @Override
            public JmxObjectInfo getJmxObjectInfo() {
                return new JmxObjectInfo("Test", this, MyMXBean.class);
            }
        }

        final MyMXBean probe = new MyMXBean();
        MockedJmxRegistry service = new MockedJmxRegistry();
        service.validate(probe.getJmxObjectInfo());
    }

    @Test
    public void managedInterfaceGood() {
        class MyMXBean implements JmxManageable, TestMXBean {
            public int getInt() { return 1; }

            @Override
            public JmxObjectInfo getJmxObjectInfo() {
                return new JmxObjectInfo("Test", this, MyMXBean.class);
            }
        }

        final MyMXBean probe = new MyMXBean();
        MockedJmxRegistry service = new MockedJmxRegistry();
        service.validate(probe.getJmxObjectInfo());
    }

    @Test
    public void managedSubInterfaceGood() {
        class MyMXBean implements JmxManageable, SubInterface {
            public int getInt() { return 1; }

            @Override
            public JmxObjectInfo getJmxObjectInfo() {
                return new JmxObjectInfo("Test", this, MyMXBean.class);
            }
        }

        final MyMXBean probe = new MyMXBean();
        MockedJmxRegistry service = new MockedJmxRegistry();
        service.validate(probe.getJmxObjectInfo());
    }

    @Test
    public void registerThenStart() {
        MockedJmxRegistry service = new MockedJmxRegistry();
        assertRegisteredServices(service);

        service.register(new GoodService("Alpha"));
        assertRegisteredServices(service);

        service.start();
        assertRegisteredServices(service, "com.akiban:type=Alpha");

        service.stop();
        assertRegisteredServices(service);
    }

    @Test
    public void registerAfterStarting() {
        MockedJmxRegistry service = new MockedJmxRegistry();
        assertRegisteredServices(service);

        service.start();
        service.register(new GoodService("Alpha"));
        assertRegisteredServices(service, "com.akiban:type=Alpha");

        service.stop();
        assertRegisteredServices(service);
    }

    @Test
    public void registerThenRestart() {
        MockedJmxRegistry service = new MockedJmxRegistry();
        assertRegisteredServices(service);

        service.register(new GoodService("Alpha"));
        assertRegisteredServices(service);

        service.start();
        assertRegisteredServices(service, "com.akiban:type=Alpha");

        service.stop();
        assertRegisteredServices(service);

        service.start();
        assertRegisteredServices(service, "com.akiban:type=Alpha");
    }

    @Test
    public void registerInterfaceTwice() {
        final MockedJmxRegistry service;
        try {
            service = new MockedJmxRegistry();
            assertRegisteredServices(service);
            service.register(new GoodService("Alpha"));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        service.register(new GoodService("Beta"));
        service.start();
        assertRegisteredServices(service, "com.akiban:type=Alpha", "com.akiban:type=Beta");
    }

    @Test(expected=JmxRegistrationException.class)
    public void registerNameTwice() {
        final MockedJmxRegistry service;
        try {
            service = new MockedJmxRegistry();
            assertRegisteredServices(service);
            service.register(new GoodService("Alpha"));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        service.register(new GoodService("Alpha"));
    }

    private void assertRegisteredServices(MockedJmxRegistry registry, String... actuals) {
        assertRegisteredServices(registry.getMBeanServer().getRegisteredObjectNames(), actuals);
    }

    private void assertRegisteredServices(Set<ObjectName> actual, String... expecteds) {
        Set<ObjectName> expectedSet = new HashSet<ObjectName>();
        for (String expected : expecteds) {
            try {
                expectedSet.add(new ObjectName(expected));
            } catch (MalformedObjectNameException e) {
                throw new RuntimeException(e);
            }
        }
        assertEquals("registered services", expectedSet, actual);
    }
}
