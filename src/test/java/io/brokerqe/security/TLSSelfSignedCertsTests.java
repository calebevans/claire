/*
 * Copyright Broker QE authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.brokerqe.security;

import io.brokerqe.Constants;
import io.brokerqe.ResourceManager;
import io.brokerqe.operator.ArtemisFileProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(Constants.TAG_JAAS)
@Tag(Constants.TAG_TLS)
public class TLSSelfSignedCertsTests extends TLSAuthorizationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(TLSSelfSignedCertsTests.class);
    @BeforeAll
    void setupDeployment() {
        testNamespace = getRandomNamespaceName("auth-ss-tests", 6);
        setupDefaultClusterOperator(testNamespace);
        setupCertificates();
    }

    @SuppressWarnings({"checkstyle:MethodLength"})
    void setupCertificates() {
        tlsAddress = ResourceManager.createArtemisAddress(testNamespace, ArtemisFileProvider.getAddressQueueExampleFile());
        Instant now = Instant.now();
        // START OF CERTIFICATE MAGIC
        Map<String, KeyStoreData> entityKeystores = new HashMap<>();

        // C=CZ, L=Brno, O=ArtemisCloud, OU=" + ou + ", CN=" + kubernetesClient.getMasterUrl().getHost().replace("api", "*");
        producerCertData = new CertificateData("producer", CertificateManager.generateArtemisCloudDN("tls-tests", "producer"));
        consumerCertData = new CertificateData("consumer", CertificateManager.generateArtemisCloudDN("tls-tests", "consumer"));
        browserCertData = new CertificateData("browser", CertificateManager.generateArtemisCloudDN("tls-tests", "browser"));
        // certificate valid <-30, -10> days ago (expired)
        expiredBeforeCertData = new CertificateData("expiredBefore", CertificateManager.generateArtemisCloudDN("tls-tests", "expiredBefore"), null,
            Date.from(now.minus(Duration.ofDays(30L))), Date.from(now.minus(Duration.ofDays(10L))));

        // certificate valid <+10, +30> days in future (not valid yet)
        expiredAfterCertData = new CertificateData("expiredAfter", CertificateManager.generateArtemisCloudDN("tls-tests", "expiredAfter"), null,
            Date.from(now.plus(Duration.ofDays(10L))), Date.from(now.plus(Duration.ofDays(30L))));

        // certificate invalid validity startDate in future, endDate in past
        expiredCertData = new CertificateData("expired", CertificateManager.generateArtemisCloudDN("tls-tests", "expired"), null,
            Date.from(now.plus(Duration.ofDays(10L))), Date.from(now.minus(Duration.ofDays(10L))));

        producerKeystores = CertificateManager.createEntityKeystores(producerCertData, "producerPass");
        consumerKeystores = CertificateManager.createEntityKeystores(consumerCertData, "consumerPass");
        browserKeystores = CertificateManager.createEntityKeystores(browserCertData, "browserPass");
        expiredBeforeKeystores = CertificateManager.createEntityKeystores(expiredBeforeCertData, "expiredPass");
        expiredAfterKeystores = CertificateManager.createEntityKeystores(expiredAfterCertData, "expiredPass");
        expiredKeystores = CertificateManager.createEntityKeystores(expiredCertData, "expiredPass");

        entityKeystores.putAll(producerKeystores);
        entityKeystores.putAll(consumerKeystores);
        entityKeystores.putAll(browserKeystores);
        entityKeystores.putAll(expiredBeforeKeystores);
        entityKeystores.putAll(expiredAfterKeystores);
        entityKeystores.putAll(expiredKeystores);
        // END OF FIRST PART OF CERTIFICATE MAGIC

        createArtemisDeployment();

        // START OF SECOND PART OF CERTIFICATE MAGIC
        Map<String, KeyStoreData> keystores = CertificateManager.generateDefaultCertificateKeystores(
            testNamespace,
            broker,
            CertificateManager.generateDefaultBrokerDN(getKubernetesClient()),
            CertificateManager.generateDefaultClientDN(getKubernetesClient()),
            List.of(CertificateManager.generateSanDnsNames(getClient(), broker, List.of(amqpAcceptorName, Constants.WEBCONSOLE_URI_PREFIX))),
            null
        );

        KeyStoreData truststoreBrokerData = keystores.get(Constants.BROKER_TRUSTSTORE_ID);
        CertificateManager.addToTruststore(truststoreBrokerData, producerCertData.getCertificate(), producerCertData.getAlias());
        CertificateManager.addToTruststore(truststoreBrokerData, consumerCertData.getCertificate(), consumerCertData.getAlias());
        CertificateManager.addToTruststore(truststoreBrokerData, browserCertData.getCertificate(), browserCertData.getAlias());
        CertificateManager.addToTruststore(truststoreBrokerData, expiredBeforeCertData.getCertificate(), expiredBeforeCertData.getAlias());
        CertificateManager.addToTruststore(truststoreBrokerData, expiredAfterCertData.getCertificate(), expiredAfterCertData.getAlias());
        CertificateManager.addToTruststore(truststoreBrokerData, expiredCertData.getCertificate(), expiredCertData.getAlias());

        CertificateManager.addToTruststore(entityKeystores.get("producer.ts"), truststoreBrokerData.getCertificateData().getCertificate(), truststoreBrokerData.getCertificateData().getAlias());
        CertificateManager.addToTruststore(entityKeystores.get("consumer.ts"), truststoreBrokerData.getCertificateData().getCertificate(), truststoreBrokerData.getCertificateData().getAlias());
        CertificateManager.addToTruststore(entityKeystores.get("browser.ts"), truststoreBrokerData.getCertificateData().getCertificate(), truststoreBrokerData.getCertificateData().getAlias());
        CertificateManager.addToTruststore(entityKeystores.get("expiredBefore.ts"), truststoreBrokerData.getCertificateData().getCertificate(), truststoreBrokerData.getCertificateData().getAlias());
        CertificateManager.addToTruststore(entityKeystores.get("expiredAfter.ts"), truststoreBrokerData.getCertificateData().getCertificate(), truststoreBrokerData.getCertificateData().getAlias());
        CertificateManager.addToTruststore(entityKeystores.get("expired.ts"), truststoreBrokerData.getCertificateData().getCertificate(), truststoreBrokerData.getCertificateData().getAlias());

        CertificateManager.createBrokerKeystoreSecret(getClient(), brokerSecretName, keystores);
        CertificateManager.createClientKeystoreSecret(getClient(), clientSecretName, keystores);
        CertificateManager.createKeystoreSecret(getClient(), producerSecretName, producerKeystores, producerCertData.getAlias());
        CertificateManager.createKeystoreSecret(getClient(), consumerSecretName, consumerKeystores, consumerCertData.getAlias());
        CertificateManager.createKeystoreSecret(getClient(), browserSecretName, browserKeystores, browserCertData.getAlias());
        CertificateManager.createKeystoreSecret(getClient(), expiredBeforeSecretName, expiredBeforeKeystores, expiredBeforeCertData.getAlias());
        CertificateManager.createKeystoreSecret(getClient(), expiredAfterSecretName, expiredAfterKeystores, expiredAfterCertData.getAlias());
        CertificateManager.createKeystoreSecret(getClient(), expiredSecretName, expiredKeystores, expiredCertData.getAlias());
        // END OF SECOND PART OF CERTIFICATE MAGIC

        broker = ResourceManager.createArtemis(testNamespace, broker, true);
        LOGGER.info("[{}] Broker {} is up and running with CertTextLoginModule", testNamespace, brokerName);
        clients = ResourceManager.deploySecuredClientsContainer(testNamespace, List.of(producerSecretName, consumerSecretName, browserSecretName, expiredBeforeSecretName, expiredAfterSecretName, expiredSecretName));
        forbiddenAddress = ResourceManager.createArtemisAddress(testNamespace, "forbidden-address", "forbidden-queue", "anycast");
        brokerUris = getClient().getExternalAccessServiceUrlPrefixName(testNamespace, brokerName + "-" + amqpAcceptorName);
        clientsPod = getClient().getFirstPodByPrefixName(testNamespace, Constants.PREFIX_SYSTEMTESTS_CLIENTS);
    }

    @Test
    @Disabled("SelfSigned certificates are not checked for expiration validity ENTMQBR-7725")
    public void testExpiredCertificateBefore() {
    }

    @Test
    @Disabled("SelfSigned certificates are not checked for expiration validity ENTMQBR-7725")
    public void testExpiredCertificateAfter() {
    }

    @Test
    @Disabled("SelfSigned certificates are not checked for expiration validity ENTMQBR-7725")
    public void testExpiredCertificate() {
    }

}
