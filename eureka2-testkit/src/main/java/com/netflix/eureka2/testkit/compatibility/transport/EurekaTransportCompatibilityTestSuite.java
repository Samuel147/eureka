/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka2.testkit.compatibility.transport;

import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.eureka2.model.InstanceModel;
import com.netflix.eureka2.model.InterestModel;
import com.netflix.eureka2.model.Server;
import com.netflix.eureka2.model.Source;
import com.netflix.eureka2.model.instance.Delta;
import com.netflix.eureka2.model.instance.InstanceInfo;
import com.netflix.eureka2.model.instance.InstanceInfoField;
import com.netflix.eureka2.model.interest.Interest;
import com.netflix.eureka2.model.notification.ChangeNotification;
import com.netflix.eureka2.model.notification.ModifyNotification;
import com.netflix.eureka2.model.notification.StreamStateNotification;
import com.netflix.eureka2.spi.channel.*;
import com.netflix.eureka2.spi.model.*;
import com.netflix.eureka2.spi.transport.EurekaClientTransportFactory;
import com.netflix.eureka2.spi.transport.EurekaServerTransportFactory;
import com.netflix.eureka2.testkit.data.builder.SampleInstanceInfo;
import com.netflix.eureka2.testkit.internal.rx.ExtTestSubscriber;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import rx.Observable;
import rx.Subscription;
import rx.subjects.ReplaySubject;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 */
public abstract class EurekaTransportCompatibilityTestSuite {

    private ClientHello clientHello;
    private ReplicationClientHello replicationClientHello;
    private ServerHello serverHello;
    private ReplicationServerHello replicationServerHello;
    private InstanceInfo instance;
    private Delta<?> updateDelta;
    private InstanceInfo updatedInstance;

    private InstanceModel instanceModel;
    private InterestModel interestModel;
    private TransportModel transportModel;

    private final RegistrationHandler registrationAcceptor = new TestableRegistrationAcceptor();
    private final InterestHandler interestAcceptor = new TestableInterestTransportHandler();
    private final TestableReplicationTransportHandler replicationAcceptor = new TestableReplicationTransportHandler();

    private Subscription serverSubscription;
    private Server eurekaServer;

    @Before
    public void setup() throws InterruptedException {
        instanceModel = InstanceModel.getDefaultModel();
        interestModel = InterestModel.getDefaultModel();
        transportModel = TransportModel.getDefaultModel();

        clientHello = transportModel.newClientHello(instanceModel.createSource(Source.Origin.LOCAL, "testClient", 1));
        replicationClientHello = transportModel.newReplicationClientHello(instanceModel.createSource(Source.Origin.LOCAL, "replicationClient", 1), 1);
        Source serverSource = instanceModel.createSource(Source.Origin.LOCAL, "testServer", 1);
        serverHello = transportModel.newServerHello(serverSource);
        replicationServerHello = transportModel.newReplicationServerHello(serverSource);

        instance = SampleInstanceInfo.WebServer.build();
        updateDelta = InstanceModel.getDefaultModel().newDelta()
                .withId(instance.getId()).withDelta(InstanceInfoField.STATUS, InstanceInfo.Status.DOWN).build();
        updatedInstance = instance.applyDelta(updateDelta);

        BlockingQueue<EurekaServerTransportFactory.ServerContext> serverContextQueue = new LinkedBlockingQueue<>();

        ChannelPipelineFactory<InstanceInfo, InstanceInfo> registrationPipelineFactory = new ChannelPipelineFactory<InstanceInfo, InstanceInfo>() {
            @Override
            public Observable<ChannelPipeline<InstanceInfo, InstanceInfo>> createPipeline() {
                return Observable.just(new ChannelPipeline<>("registration", registrationAcceptor));
            }
        };
        ChannelPipelineFactory<Interest<InstanceInfo>, ChangeNotification<InstanceInfo>> interestPipelineFactory = new ChannelPipelineFactory<Interest<InstanceInfo>, ChangeNotification<InstanceInfo>>() {
            @Override
            public Observable<ChannelPipeline<Interest<InstanceInfo>, ChangeNotification<InstanceInfo>>> createPipeline() {
                return Observable.just(new ChannelPipeline<>("interest", interestAcceptor));
            }
        };
        ChannelPipelineFactory<ChangeNotification<InstanceInfo>, Void> replicationPipelineFactory = new ChannelPipelineFactory<ChangeNotification<InstanceInfo>, Void>() {
            @Override
            public Observable<ChannelPipeline<ChangeNotification<InstanceInfo>, Void>> createPipeline() {
                return Observable.just(new ChannelPipeline<>("replication", replicationAcceptor));
            }
        };

        serverSubscription = newServerTransportFactory().connect(0, registrationPipelineFactory, interestPipelineFactory, replicationPipelineFactory)
                .doOnNext(context -> serverContextQueue.add(context))
                .doOnError(e -> e.printStackTrace())
                .subscribe();
        EurekaServerTransportFactory.ServerContext serverContext = serverContextQueue.poll(30, TimeUnit.SECONDS);
        eurekaServer = new Server("localhost", serverContext.getPort());
    }

    @After
    public void tearDown() {
        if (serverSubscription != null) {
            serverSubscription.unsubscribe();
        }
    }

    protected abstract EurekaClientTransportFactory newClientTransportFactory();

    protected abstract EurekaServerTransportFactory newServerTransportFactory();

    @Test(timeout = 30000)
    public void testRegistrationHello() throws InterruptedException {
        RegistrationHandler clientTransport = newClientTransportFactory().newRegistrationClientTransport(eurekaServer);

        ReplaySubject<ChannelNotification<InstanceInfo>> registrations = ReplaySubject.create();
        ExtTestSubscriber<ChannelNotification<InstanceInfo>> testSubscriber = new ExtTestSubscriber<>();

        clientTransport.handle(registrations).subscribe(testSubscriber);

        // Send hello
        registrations.onNext(ChannelNotification.newHello(clientHello));
        ChannelNotification<InstanceInfo> helloReply = testSubscriber.takeNextOrWait();
        assertThat(helloReply.getKind(), is(equalTo(ChannelNotification.Kind.Hello)));
        assertThat(helloReply.getHello(), is(equalTo(serverHello)));
    }

    @Test(timeout = 30000)
    public void testRegistrationHeartbeat() throws InterruptedException {
        RegistrationHandler clientTransport = newClientTransportFactory().newRegistrationClientTransport(eurekaServer);

        ReplaySubject<ChannelNotification<InstanceInfo>> registrations = ReplaySubject.create();
        ExtTestSubscriber<ChannelNotification<InstanceInfo>> testSubscriber = new ExtTestSubscriber<>();

        clientTransport.handle(registrations).subscribe(testSubscriber);

        // Send heartbeat
        registrations.onNext(ChannelNotification.newHeartbeat());
        ChannelNotification<InstanceInfo> helloReply = testSubscriber.takeNextOrWait();
        assertThat(helloReply.getKind(), is(equalTo(ChannelNotification.Kind.Heartbeat)));
    }

    @Test(timeout = 30000)
    public void testRegistrationConnection() throws InterruptedException {
        RegistrationHandler clientTransport = newClientTransportFactory().newRegistrationClientTransport(eurekaServer);

        ReplaySubject<ChannelNotification<InstanceInfo>> registrations = ReplaySubject.create();
        ExtTestSubscriber<ChannelNotification<InstanceInfo>> testSubscriber = new ExtTestSubscriber<>();

        clientTransport.handle(registrations).subscribe(testSubscriber);

        // Send data
        registrations.onNext(ChannelNotification.newData(instance));
        ChannelNotification<InstanceInfo> confirmation = testSubscriber.takeNextOrWait();
        assertThat(confirmation.getKind(), is(equalTo(ChannelNotification.Kind.Data)));
    }

    @Test(timeout = 30000)
    public void testInterestHello() throws InterruptedException {
        InterestHandler clientTransport = newClientTransportFactory().newInterestTransport(eurekaServer);
        ReplaySubject<ChannelNotification<Interest<InstanceInfo>>> interestNotifications = ReplaySubject.create();

        ExtTestSubscriber<ChannelNotification<ChangeNotification<InstanceInfo>>> testSubscriber = new ExtTestSubscriber<>();
        clientTransport.handle(interestNotifications).subscribe(testSubscriber);

        interestNotifications.onNext(ChannelNotification.newHello(clientHello));

        ChannelNotification<ChangeNotification<InstanceInfo>> helloReply = testSubscriber.takeNextOrWait();
        assertThat(helloReply.getHello(), is(equalTo(serverHello)));
    }

    @Test(timeout = 30000)
    public void testInterestHeartbeat() throws InterruptedException {
        InterestHandler clientTransport = newClientTransportFactory().newInterestTransport(eurekaServer);
        ReplaySubject<ChannelNotification<Interest<InstanceInfo>>> interestNotifications = ReplaySubject.create();

        ExtTestSubscriber<ChannelNotification<ChangeNotification<InstanceInfo>>> testSubscriber = new ExtTestSubscriber<>();
        clientTransport.handle(interestNotifications).subscribe(testSubscriber);

        interestNotifications.onNext(ChannelNotification.newHeartbeat());

        ChannelNotification<ChangeNotification<InstanceInfo>> helloReply = testSubscriber.takeNextOrWait();
        assertThat(helloReply.getKind(), is(equalTo(ChannelNotification.Kind.Heartbeat)));
    }

    @Test
    public void testInterestConnection() throws InterruptedException {
        InterestHandler clientTransport = newClientTransportFactory().newInterestTransport(eurekaServer);
        ReplaySubject<ChannelNotification<Interest<InstanceInfo>>> interestNotifications = ReplaySubject.create();

        ExtTestSubscriber<ChannelNotification<ChangeNotification<InstanceInfo>>> testSubscriber = new ExtTestSubscriber<>();
        clientTransport.handle(interestNotifications).subscribe(testSubscriber);

        interestNotifications.onNext(ChannelNotification.newHello(clientHello));
        interestNotifications.onNext(ChannelNotification.newData(interestModel.newFullRegistryInterest()));

        ChannelNotification<ChangeNotification<InstanceInfo>> notification = testSubscriber.takeNextOrWait();
        assertThat(notification.getKind(), is(equalTo(ChannelNotification.Kind.Hello)));

        // Sequence of buffeStart / add / modify /delete / bufferEnd
        ChannelNotification<ChangeNotification<InstanceInfo>> expectedBufferStart = testSubscriber.takeNextOrWait();
        assertThat(expectedBufferStart.getData().getKind(), is(equalTo(ChangeNotification.Kind.BufferSentinel)));
        StreamStateNotification<InstanceInfo> bufferStartUpdate = (StreamStateNotification<InstanceInfo>) expectedBufferStart.getData();
        assertThat(bufferStartUpdate.getBufferState(), is(equalTo(StreamStateNotification.BufferState.BufferStart)));

        ChannelNotification<ChangeNotification<InstanceInfo>> expectedAdd = testSubscriber.takeNextOrWait();
        assertThat(expectedAdd.getData().getKind(), is(equalTo(ChangeNotification.Kind.Add)));
        assertThat(expectedAdd.getData().getData(), is(equalTo(instance)));

        ChannelNotification<ChangeNotification<InstanceInfo>> expectedModify = testSubscriber.takeNextOrWait();
        assertThat(expectedModify.getData().getKind(), is(equalTo(ChangeNotification.Kind.Modify)));
        assertThat(expectedModify.getData().getData(), is(equalTo(updatedInstance)));

        ChannelNotification<ChangeNotification<InstanceInfo>> expectedDelete = testSubscriber.takeNextOrWait();
        assertThat(expectedDelete.getData().getKind(), is(equalTo(ChangeNotification.Kind.Delete)));
        assertThat(expectedDelete.getData().getData(), is(equalTo(updatedInstance)));

        ChannelNotification<ChangeNotification<InstanceInfo>> expectedBufferEnd = testSubscriber.takeNextOrWait();
        assertThat(expectedBufferEnd.getData().getKind(), is(equalTo(ChangeNotification.Kind.BufferSentinel)));
        StreamStateNotification<InstanceInfo> bufferEndUpdate = (StreamStateNotification<InstanceInfo>) expectedBufferEnd.getData();
        assertThat(bufferEndUpdate.getBufferState(), is(equalTo(StreamStateNotification.BufferState.BufferEnd)));
    }

    @Test(timeout = 30000)
    public void testReplicationHello() throws InterruptedException {
        ReplicationHandler clientTransport = newClientTransportFactory().newReplicationTransport(eurekaServer);
        ReplaySubject<ChannelNotification<ChangeNotification<InstanceInfo>>> replicationUpdates = ReplaySubject.create();

        ExtTestSubscriber<ChannelNotification<Void>> testSubscriber = new ExtTestSubscriber<>();
        clientTransport.handle(replicationUpdates).subscribe(testSubscriber);

        replicationUpdates.onNext(ChannelNotification.newHello(replicationClientHello));
        ChannelNotification<Void> helloReply = testSubscriber.takeNextOrWait();

        assertThat(helloReply.getKind(), is(equalTo(ChannelNotification.Kind.Hello)));
        assertThat(helloReply.getHello(), is(equalTo(replicationServerHello)));
    }

    @Test(timeout = 30000)
    public void testReplicationHeartbeat() throws InterruptedException {
        ReplicationHandler clientTransport = newClientTransportFactory().newReplicationTransport(eurekaServer);
        ReplaySubject<ChannelNotification<ChangeNotification<InstanceInfo>>> replicationUpdates = ReplaySubject.create();

        ExtTestSubscriber<ChannelNotification<Void>> testSubscriber = new ExtTestSubscriber<>();
        clientTransport.handle(replicationUpdates).subscribe(testSubscriber);

        replicationUpdates.onNext(ChannelNotification.newHeartbeat());
        ChannelNotification<Void> heartbeatReply = testSubscriber.takeNextOrWait();

        assertThat(heartbeatReply.getKind(), is(equalTo(ChannelNotification.Kind.Heartbeat)));
    }

    @Test(timeout = 30000)
    public void testReplicationConnection() throws InterruptedException {
        ReplicationHandler clientTransport = newClientTransportFactory().newReplicationTransport(eurekaServer);
        ReplaySubject<ChannelNotification<ChangeNotification<InstanceInfo>>> replicationUpdates = ReplaySubject.create();

        ExtTestSubscriber<ChannelNotification<Void>> testSubscriber = new ExtTestSubscriber<>();
        clientTransport.handle(replicationUpdates).subscribe(testSubscriber);

        ChangeNotification<InstanceInfo> addNotification = new ChangeNotification<InstanceInfo>(ChangeNotification.Kind.Add, instance);
        replicationUpdates.onNext(ChannelNotification.newHello(replicationClientHello));
        replicationUpdates.onNext(ChannelNotification.newData(addNotification));

        assertThat(replicationAcceptor.takeNextUpdate().getKind(), is(equalTo(ChangeNotification.Kind.Add)));
    }

    class TestableRegistrationAcceptor implements RegistrationHandler {
        @Override
        public void init(ChannelContext<InstanceInfo, InstanceInfo> channelContext) {
        }

        @Override
        public Observable<ChannelNotification<InstanceInfo>> handle(Observable<ChannelNotification<InstanceInfo>> registrationUpdates) {
            return Observable.create(subscriber -> {
                registrationUpdates.subscribe(
                        next -> {
                            if (next.getKind() == ChannelNotification.Kind.Hello) {
                                subscriber.onNext(ChannelNotification.<ServerHello, InstanceInfo>newHello(serverHello));
                            } else {
                                subscriber.onNext(next);
                            }
                        },
                        e -> e.printStackTrace()
                );
            });
        }
    }

    class TestableInterestTransportHandler implements InterestHandler {
        @Override
        public void init(ChannelContext<Interest<InstanceInfo>, ChangeNotification<InstanceInfo>> channelContext) {
        }

        @Override
        public Observable<ChannelNotification<ChangeNotification<InstanceInfo>>> handle(Observable<ChannelNotification<Interest<InstanceInfo>>> interests) {
            return Observable.create(subscriber -> {

                ChangeNotification<InstanceInfo> addNotification = new ChangeNotification<InstanceInfo>(ChangeNotification.Kind.Add, instance);
                ChangeNotification<InstanceInfo> modifyNotification = new ModifyNotification<InstanceInfo>(updatedInstance, Collections.singleton(updateDelta));
                ChangeNotification<InstanceInfo> deleteNotification = new ChangeNotification<InstanceInfo>(ChangeNotification.Kind.Delete, updatedInstance);

                interests
                        .doOnNext(interest -> {
                            switch (interest.getKind()) {
                                case Hello:
                                    ChannelNotification<ChangeNotification<InstanceInfo>> serverHelloNotification = ChannelNotification.newHello(serverHello);
                                    subscriber.onNext(serverHelloNotification);
                                    break;
                                case Heartbeat:
                                    subscriber.onNext(ChannelNotification.<ChangeNotification<InstanceInfo>>newHeartbeat());
                                    break;
                                case Data:
                                    ChangeNotification<InstanceInfo> bufferStart = StreamStateNotification.bufferStartNotification(interest.getData());
                                    ChangeNotification<InstanceInfo> bufferEnd = StreamStateNotification.bufferEndNotification(interest.getData());
                                    subscriber.onNext(ChannelNotification.newData(bufferStart));
                                    subscriber.onNext(ChannelNotification.newData(addNotification));
                                    subscriber.onNext(ChannelNotification.newData(modifyNotification));
                                    subscriber.onNext(ChannelNotification.newData(deleteNotification));
                                    subscriber.onNext(ChannelNotification.newData(bufferEnd));
                            }
                        })
                        .doOnError(e -> e.printStackTrace())
                        .subscribe();
            });
        }
    }

    class TestableReplicationTransportHandler implements ReplicationHandler {

        private final BlockingQueue<ChangeNotification<InstanceInfo>> replicationUpdates = new LinkedBlockingQueue<>();

        @Override
        public void init(ChannelContext<ChangeNotification<InstanceInfo>, Void> channelContext) {
        }

        @Override
        public Observable<ChannelNotification<Void>> handle(Observable<ChannelNotification<ChangeNotification<InstanceInfo>>> inputStream) {
            return Observable.create(subscriber -> {
                AtomicReference<Source> clientSourceRef = new AtomicReference<Source>();
                inputStream
                        .doOnNext(replicationNotification -> {
                            switch (replicationNotification.getKind()) {
                                case Hello:
                                    ChannelNotification<Void> serverHelloNotification = ChannelNotification.newHello(replicationServerHello);
                                    clientSourceRef.set(serverHelloNotification.getHello());
                                    subscriber.onNext(serverHelloNotification);
                                    break;
                                case Heartbeat:
                                    subscriber.onNext(ChannelNotification.<Void>newHeartbeat());
                                    break;
                                case Data:
                                    replicationUpdates.add(replicationNotification.getData());
                            }
                        })
                        .doOnError(e -> e.printStackTrace())
                        .subscribe();
            });
        }

        public ChangeNotification<InstanceInfo> takeNextUpdate() throws InterruptedException {
            return replicationUpdates.poll(5, TimeUnit.SECONDS);
        }
    }
}
