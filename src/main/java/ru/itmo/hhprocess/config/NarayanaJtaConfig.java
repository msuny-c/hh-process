package ru.itmo.hhprocess.config;

import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionSynchronizationRegistryImple;
import com.arjuna.ats.jta.common.jtaPropertyManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NarayanaJtaConfig {

    @Bean
    public static BeanFactoryPostProcessor narayanaArjunaCoreSynchronizationRegistryConfigurer() {
        return new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                jtaPropertyManager.getJTAEnvironmentBean()
                        .setTransactionSynchronizationRegistry(new TransactionSynchronizationRegistryImple());
            }
        };
    }
}
