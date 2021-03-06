package aQute.bnd.repository.maven.pom.provider;

import static aQute.bnd.osgi.repository.BridgeRepository.addInformationCapability;
import static org.osgi.framework.namespace.IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE;
import static org.osgi.framework.namespace.IdentityNamespace.IDENTITY_NAMESPACE;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Resource;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.osgi.resource.CapabilityBuilder;
import aQute.bnd.osgi.resource.ResourceBuilder;
import aQute.maven.api.Archive;
import aQute.maven.api.IPom.Dependency;
import aQute.maven.api.MavenScope;
import aQute.maven.api.Program;
import aQute.maven.api.Revision;
import aQute.maven.provider.MavenRepository;
import aQute.maven.provider.POM;

class Traverser {
	final static Logger							logger		= LoggerFactory.getLogger(Traverser.class);
	static final Resource						DUMMY		= new ResourceBuilder().build();
	final ConcurrentHashMap<Archive,Resource>	resources	= new ConcurrentHashMap<>();
	final Executor								executor;
	final Revision								revision;
	final URI									resource;
	final AtomicInteger							count		= new AtomicInteger(0);
	final Deferred<Map<Archive,Resource>>		deferred	= new Deferred<>();
	final MavenRepository						repo;
	final Set<String>							error		= Collections.synchronizedSet(new HashSet<String>());

	Traverser(MavenRepository repo, Revision revision, Executor executor) {
		this.repo = repo;
		this.revision = revision;
		this.executor = executor;
		this.resource = null;
	}

	Traverser(MavenRepository repo, URI revision, Executor executor) {
		this.repo = repo;
		this.revision = null;
		this.executor = executor;
		this.resource = revision;
	}

	Promise<Map<Archive,Resource>> getResources() throws MalformedURLException, IOException, Exception {
		if (deferred.getPromise().isDone())
			throw new IllegalStateException();

		if (resource != null) {
			POM pom = new POM(repo, resource.toURL().openStream());
			parsePom(pom, "<>");
		} else {
			parse(revision.archive("jar", null), "<>");
		}
		return deferred.getPromise();
	}

	private void parse(final Archive archive, final String parent) {
		//
		// Prune duplicates by adding the archive to a set. We
		// use a dummy for the resource, the resource is set later
		//

		Resource prev = resources.putIfAbsent(archive, DUMMY);
		if (prev != null)
			return;

		//
		// Every parse must be matched by a background
		// execution of parseArchive. So we count the enters
		// and then decrement at the end of the background task
		// if we go to 0 then we've done it all
		//

		count.incrementAndGet();
		executor.execute(new Runnable() {

			@Override
			public void run() {
				try {
					logger.trace("parse archive {}", archive);
					parseArchive(archive);
				} catch (Throwable throwable) {
					logger.trace(" failed to parse archive {}: {}", archive, throwable);
					ResourceBuilder rb = new ResourceBuilder();
					String bsn = archive.revision.program.toString();
					Version version = toFrameworkVersion(archive.revision.version.getOSGiVersion());
					addReserveIdentity(rb, bsn, version);
					addInformationCapability(rb, archive.toString(), parent, throwable);
					resources.put(archive, rb.build());

					error.add(archive + " from " + parent + throwable.getMessage());
				} finally {
					//
					// If count goes to zero, then we
					if (count.decrementAndGet() == 0) {
						finish();
					}
				}
			}

		});
	}

	private Version toFrameworkVersion(aQute.bnd.version.Version v) {

		return new Version(v.getMajor(), v.getMinor(), v.getMicro(), v.getQualifier());
	}

	void finish() {
		deferred.resolve(prune(resources));
	}

	/*
	 * Remove the pom only archives, they've not overwritten the dummy value
	 * @param resources the resources parsed
	 * @return the pruned resources
	 */
	private Map<Archive,Resource> prune(ConcurrentHashMap<Archive,Resource> resources) {
		for (Iterator<Entry<Archive,Resource>> e = resources.entrySet().iterator(); e.hasNext();) {
			Entry<Archive,Resource> next = e.next();
			if (next.getValue() == DUMMY)
				e.remove();
		}
		return resources;
	}

	private void parseArchive(Archive archive) throws Exception {
		POM pom = repo.getPom(archive.revision);
		String parent = archive.revision.toString();

		parsePom(pom, parent);

		if (!pom.isPomOnly())
			parseResource(archive, parent);
	}

	public void parsePom(POM pom, String parent) throws Exception {
		Map<Program,Dependency> dependencies = pom.getDependencies(EnumSet.of(MavenScope.compile, MavenScope.runtime),
				false);
		for (Dependency d : dependencies.values()) {
			parse(d.getArchive(), parent);
		}
	}

	private void parseResource(Archive archive, String parent) throws Exception {
		ResourceBuilder rb = new ResourceBuilder();

		Version frameworkVersion = toFrameworkVersion(archive.revision.version.getOSGiVersion());
		String bsn = archive.revision.program.toString();

		try {
			File binary = repo.get(archive).getValue();

			if (!rb.addFile(binary, binary.toURI())) {
				// no identity
				addReserveIdentity(rb, bsn, frameworkVersion);
			}
			addInformationCapability(rb, archive.toString(), parent, null);
		} catch (Exception e) {
			addReserveIdentity(rb, bsn, frameworkVersion);
			addInformationCapability(rb, archive.toString(), parent, e);
		}
		resources.put(archive, rb.build());
	}

	void addReserveIdentity(ResourceBuilder rb, String bsn, Version version) {
		try {
			CapabilityBuilder c = new CapabilityBuilder(IDENTITY_NAMESPACE);
			c.addAttribute(IDENTITY_NAMESPACE, bsn);
			c.addAttribute(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE, version);
			c.addAttribute(CAPABILITY_TYPE_ATTRIBUTE, "application/java-archive");
			rb.addCapability(c);
		} catch (Exception ee) {
			ee.printStackTrace();
		}
	}

}
