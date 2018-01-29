/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.discovery.consul

import io.reactivex.Flowable
import org.particleframework.context.ApplicationContext
import org.particleframework.core.io.socket.SocketUtils
import org.particleframework.discovery.DiscoveryClient
import org.particleframework.discovery.ServiceInstance
import org.particleframework.discovery.consul.client.v1.ConsulClient
import org.particleframework.discovery.consul.client.v1.HealthEntry
import org.particleframework.discovery.consul.client.v1.NewServiceEntry
import org.particleframework.http.HttpStatus
import org.particleframework.http.client.HttpClient
import org.particleframework.http.client.exceptions.HttpClientResponseException
import org.particleframework.runtime.server.EmbeddedServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.util.concurrent.PollingConditions

/**
 * @author graemerocher
 * @since 1.0
 */
class ConsulMockAutoRegistrationSpec extends Specification {
    @Shared
    int serverPort = SocketUtils.findAvailableTcpPort()
    @AutoCleanup
    @Shared
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer,
            ['particle.application.name': 'test-auto-reg',
             'particle.server.port'     : serverPort,
             'consul.host'              : 'localhost',
             'consul.port'              : serverPort]
    )
    @Shared
    ConsulClient client = embeddedServer.applicationContext.getBean(ConsulClient)
    @Shared
    DiscoveryClient discoveryClient = embeddedServer.applicationContext.getBean(DiscoveryClient)

    void 'test mock server'() {
        when:
        def status = Flowable.fromPublisher(client.register(new NewServiceEntry("test-service"))).blockingFirst()
        then:
        status
        Flowable.fromPublisher(client.services).blockingFirst()
        Flowable.fromPublisher(discoveryClient.getInstances('test-service')).blockingFirst()
        Flowable.fromPublisher(client.deregister('test-service')).blockingFirst()
    }

    void 'test that the service is automatically registered with Consul'() {

        given:
        PollingConditions conditions = new PollingConditions(timeout: 3)

        expect:
        conditions.eventually {
            List<ServiceInstance> instances = Flowable.fromPublisher(discoveryClient.getInstances('test-auto-reg')).blockingFirst()
            instances.size() == 1
            instances[0].id.contains('test-auto-reg')
            instances[0].port == embeddedServer.getPort()
            instances[0].host == embeddedServer.getHost()
        }
    }

    void 'test that the service is automatically de-registered with Consul'() {

        when: "creating another server instance"
        def serviceName = 'another-server'
        EmbeddedServer anotherServer = ApplicationContext.run(EmbeddedServer, ['particle.application.name': serviceName,
                                                                               'consul.host'              : 'localhost',
                                                                               'consul.port'              : serverPort])

        PollingConditions conditions = new PollingConditions(timeout: 3)

        then:
        conditions.eventually {
            List<ServiceInstance> instances = Flowable.fromPublisher(discoveryClient.getInstances(serviceName)).blockingFirst()
            instances.size() == 1
            instances[0].id.contains(serviceName)
            instances[0].port == anotherServer.getPort()
            instances[0].host == anotherServer.getHost()
        }

        when: "stopping the server"
        anotherServer.stop()

        then:
        conditions.eventually {
            List<ServiceInstance> instances = Flowable.fromPublisher(discoveryClient.getInstances(serviceName)).blockingFirst()
            instances.size() == 0
            !instances.find { it.id == serviceName }
        }
    }

    void "test that a service can be registered with tags"() {
        when: "creating another server instance"
        def serviceName = 'another-server'
        EmbeddedServer anotherServer = ApplicationContext.run(EmbeddedServer, ['particle.application.name': serviceName,
                                                                               'consul.registration.tags' : ['foo', 'bar'],
                                                                               'consul.host'              : 'localhost',
                                                                               'consul.port'              : serverPort])

        PollingConditions conditions = new PollingConditions(timeout: 3)

        then:
        conditions.eventually {
            List<HealthEntry> entry = Flowable.fromPublisher(client.getHealthyServices(serviceName)).blockingFirst()
            entry.size() == 1
            entry[0].service.tags == ['foo', 'bar']
        }

        cleanup:
        anotherServer.stop()
    }

    void "test that a asl token can be configured"() {
        when: "creating another server instance"
        def serviceName = 'another-server'
        EmbeddedServer consulServer = ApplicationContext.run(EmbeddedServer, ['consul.aslToken'          : ['xxxxxxxxxxxx']])

        EmbeddedServer anotherServer = ApplicationContext.run(EmbeddedServer, ['particle.application.name': serviceName,
                                                                               'consul.aslToken'          : ['xxxxxxxxxxxx'],
                                                                               'consul.host'              : 'localhost',
                                                                               'consul.port'              : consulServer.getPort()])

        ConsulClient consulClient = anotherServer.applicationContext.getBean(ConsulClient)
        PollingConditions conditions = new PollingConditions(timeout: 3)

        then:
        conditions.eventually {
            List<HealthEntry> entry = Flowable.fromPublisher(consulClient.getHealthyServices(serviceName)).blockingFirst()
            entry.size() == 1
        }

        when:"A regular client tries to talk to consul without the token"
        HttpClient client = embeddedServer.getApplicationContext().createBean(HttpClient, consulServer.getURL())
        client.toBlocking().retrieve('/v1/agent/services')


        then:
        HttpClientResponseException e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN


        cleanup:
        anotherServer.stop()
        consulServer.stop()
    }
}
