package io.quarkus.arc.deployment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.enterprise.inject.CreationException;

import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem.BeanConfiguratorBuildItem;
import io.quarkus.arc.runtime.ArcRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.util.HashUtil;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

public class SyntheticBeansProcessor {

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void initStatic(ArcRecorder recorder, List<SyntheticBeanBuildItem> syntheticBeans,
            BeanRegistrationPhaseBuildItem beanRegistration, BuildProducer<BeanConfiguratorBuildItem> configurators) {

        Map<String, Supplier<?>> suppliersMap = new HashMap<>();

        for (SyntheticBeanBuildItem bean : syntheticBeans) {
            if (bean.isStaticInit()) {
                initSyntheticBean(recorder, suppliersMap, beanRegistration, bean);
            }
        }
        // Init the map of bean instances
        recorder.initStaticSupplierBeans(suppliersMap);
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void initRuntime(ArcRecorder recorder, List<SyntheticBeanBuildItem> syntheticBeans,
            BeanRegistrationPhaseBuildItem beanRegistration, BuildProducer<BeanConfiguratorBuildItem> configurators) {

        Map<String, Supplier<?>> suppliersMap = new HashMap<>();

        for (SyntheticBeanBuildItem bean : syntheticBeans) {
            if (!bean.isStaticInit()) {
                initSyntheticBean(recorder, suppliersMap, beanRegistration, bean);
            }
        }
        recorder.initRuntimeSupplierBeans(suppliersMap);
    }

    private void initSyntheticBean(ArcRecorder recorder, Map<String, Supplier<?>> suppliersMap,
            BeanRegistrationPhaseBuildItem beanRegistration, SyntheticBeanBuildItem bean) {
        DotName implClazz = bean.configurator().getImplClazz();
        String name = createName(implClazz.toString(), bean.configurator().getQualifiers().toString());
        if (bean.configurator().runtimeValue != null) {
            suppliersMap.put(name, recorder.createSupplier(bean.configurator().runtimeValue));
        } else {
            suppliersMap.put(name, bean.configurator().supplier);
        }
        beanRegistration.getContext().configure(implClazz)
                .read(bean.configurator())
                .creator(creator(name))
                .done();
    }

    private String createName(String beanClass, String qualifiers) {
        return beanClass.replace(".", "_") + "_"
                + HashUtil.sha1(qualifiers);
    }

    private Consumer<MethodCreator> creator(String name) {
        return new Consumer<MethodCreator>() {
            @Override
            public void accept(MethodCreator m) {
                ResultHandle staticMap = m
                        .readStaticField(FieldDescriptor.of(ArcRecorder.class, "supplierMap", Map.class));
                ResultHandle supplier = m.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(Map.class, "get", Object.class, Object.class), staticMap,
                        m.load(name));
                // Throw an exception if no supplier is found
                m.ifNull(supplier).trueBranch().throwException(CreationException.class,
                        "Synthetic bean instance not initialized yet: " + name);
                ResultHandle result = m.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(Supplier.class, "get", Object.class),
                        supplier);
                m.returnValue(result);
            }
        };
    }

}
