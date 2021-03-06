package org.sagebionetworks.template.repo.beanstalk.ssl;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.function.Consumer;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.sagebionetworks.template.config.Configuration;
import org.sagebionetworks.template.FileProvider;
import org.sagebionetworks.template.TemplateGuiceModule;
import org.sagebionetworks.war.WarAppender;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

public class ElasticBeanstalkExtentionBuilderImpl implements ElasticBeanstalkExtentionBuilder {


	public static final String SSL_CONF = "ssl.conf";

	public static final String SECURITY_CONF = "security.conf";

	public static final String HTTPD_CONF_D = "httpd/conf.d";

	public static final String TEMPLATES_REPO_EBEXTENSIONS_HTTPS_SSL_CONF = "templates/repo/ebextensions/https-ssl.conf";

	public static final String TEMPLATES_REPO_EBEXTENSIONS_SECURITY_CONF = "templates/repo/ebextensions/security.conf";

	public static final String INSTANCE_CONFIG = "instance.config";

	public static final String DOT_EBEXTENSIONS = ".ebextensions";

	public static final String TEMPLATE_EBEXTENSIONS_INSTANCE_CONFIG = "templates/repo/ebextensions/instance.config";

	CertificateBuilder certificateBuilder;
	VelocityEngine velocityEngine;
	Configuration configuration;
	WarAppender warAppender;
	FileProvider fileProvider;

	@Inject
	public ElasticBeanstalkExtentionBuilderImpl(CertificateBuilder certificateBuilder, VelocityEngine velocityEngine,
			Configuration configuration, WarAppender warAppender, FileProvider fileProvider) {
		super();
		this.certificateBuilder = certificateBuilder;
		this.velocityEngine = velocityEngine;
		this.configuration = configuration;
		this.warAppender = warAppender;
		this.fileProvider = fileProvider;
	}

	@Override
	public File copyWarWithExtensions(File warFile) {
		VelocityContext context = new VelocityContext();
		context.put("s3bucket", configuration.getConfigurationBucket());
		// Get the certificate information
		context.put("certificates", certificateBuilder.buildNewX509CertificatePair());



		// add the files to the copy of the war
		return warAppender.appendFilesCopyOfWar(warFile, new Consumer<File>() {

			@Override
			public void accept(File directory) {
				// ensure the .ebextensions directory exists
				File ebextensionsDirectory = fileProvider.createNewFile(directory, DOT_EBEXTENSIONS);
				ebextensionsDirectory.mkdirs();
				// ensure the .ebextensions/httpd/conf.d directory exists.
				File confDDirectory = fileProvider.createNewFile(ebextensionsDirectory, HTTPD_CONF_D);
				confDDirectory.mkdirs();
				// https-instance.config
				Template httpInstanceTempalte = velocityEngine.getTemplate(TEMPLATE_EBEXTENSIONS_INSTANCE_CONFIG);
				File resultFile = fileProvider.createNewFile(ebextensionsDirectory, INSTANCE_CONFIG);
				addTemplateAsFileToDirectory(httpInstanceTempalte, context, resultFile);
				// SSL conf
				resultFile = fileProvider.createNewFile(confDDirectory, SSL_CONF);
				Template sslconf = velocityEngine.getTemplate(TEMPLATES_REPO_EBEXTENSIONS_HTTPS_SSL_CONF);
				addTemplateAsFileToDirectory(sslconf, context, resultFile);
				// ModSecurity conf
				resultFile = fileProvider.createNewFile(confDDirectory, SECURITY_CONF);
				Template modSecurityConf = velocityEngine.getTemplate(TEMPLATES_REPO_EBEXTENSIONS_SECURITY_CONF);
				addTemplateAsFileToDirectory(modSecurityConf, context, resultFile);
			}
		});

	}

	/**
	 * Merge the passed template and context and save the results as a new file in
	 * the passed directory with the given name.
	 * 
	 * @param tempalte
	 * @param context
	 * @param resultFile
	 */
	public void addTemplateAsFileToDirectory(Template tempalte, VelocityContext context, File resultFile) {
		try (Writer writer = fileProvider
				.createFileWriter(resultFile)) {
			tempalte.merge(context, writer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Helper to run the actual builder
	 * @param args
	 */
	public static void main(String[] args) {
		Injector injector = Guice.createInjector(new TemplateGuiceModule());
		ElasticBeanstalkExtentionBuilder builder = injector.getInstance(ElasticBeanstalkExtentionBuilder.class);
		File resultWar = builder.copyWarWithExtensions(new File(args[0]));
		System.out.println(resultWar.getAbsolutePath());
	}

}
