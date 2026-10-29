package io.setu.server;

import io.setu.security.tls.TlsConfiguration;
import io.setu.security.tls.TlsContextFactory;

record TrainingTls(TlsConfiguration server, TlsConfiguration client, TlsContextFactory contexts) { }
