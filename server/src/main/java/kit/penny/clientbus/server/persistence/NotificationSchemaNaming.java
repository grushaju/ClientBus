package kit.penny.clientbus.server.persistence;

import kit.penny.clientbus.server.persistence.entity.OutboundCallbackEntity;
import kit.penny.clientbus.server.persistence.entity.notification.NotificationRecord;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;

/** Names Hibernate's generated enum CHECKs before schema export, alongside the explicit entity constraints. */
public final class NotificationSchemaNaming implements Integrator {
    @Override
    public void integrate(Metadata metadata, BootstrapContext context, SessionFactoryImplementor factory) {
        for (var entity : metadata.getEntityBindings()) {
            Class<?> mappedClass = entity.getMappedClass();
            if (mappedClass == null || (!NotificationRecord.class.isAssignableFrom(mappedClass)
                    && mappedClass != OutboundCallbackEntity.class)) continue;
            var table = entity.getTable();
            for (var column : table.getColumns()) {
                int number = 0;
                for (var check : column.getCheckConstraints()) {
                    if (check.isAnonymous()) {
                        check.setName(table.getName() + column.getName() + "ck" + (number == 0 ? "" : number));
                        number++;
                    }
                }
            }
        }
    }
}
