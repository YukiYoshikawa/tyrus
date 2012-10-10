package org.glassfish.tyrus.platform;

import javax.net.websocket.Decoder;
import javax.net.websocket.Encoder;
import javax.net.websocket.EndpointConfiguration;
import java.util.List;

/**
 * Default configuration implementation.
 *
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 */
public class DefaultEndpointConfiguration implements EndpointConfiguration{

    /**
     * Model that represents the {@link javax.net.websocket.Endpoint} this configuration belongs to.
     */
    Model model;

    @Override
    public List<Encoder> getEncoders() {
        return model.getEncoders();
    }

    @Override
    public List<Decoder> getDecoders() {
        return model.getDecoders();
    }

    /**
     * Set the {@link Model} for this endpoint configuration.
     *
     * @param model {@link Model} which will be set to the configuration.
     */
    public void setModel(Model model) {
        this.model = model;
    }
}
