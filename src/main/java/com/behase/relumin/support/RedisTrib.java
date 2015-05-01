package com.behase.relumin.support;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;

/**
 * Almost Clone of redis-trib.rb
 * @author Ryosuke Hasebe
 *
 */
@Slf4j
public class RedisTrib implements Closeable {
	private static final int CLUSTER_HASH_SLOTS = 16384;

	private int replicas;
	private List<TribClusterNode> nodes = Lists.newArrayList();

	public void getAllocSlotsForCreateCluster(int replicas, List<String> hostAndPorts) throws RedisTribException {
		log.debug("hostAndPorts={}", hostAndPorts);
		this.replicas = replicas;

		for (String hostAndPort : hostAndPorts) {
			TribClusterNode node = new TribClusterNode(hostAndPort);
			node.connect(true);
			node.assertCluster();
			node.loadInfo();
			node.assertEmpty();
			addNodes(node);
		}

		checkCreateParameters();
		allocSlots();
	}

	void checkCreateParameters() throws RedisTribException {
		int masters = nodes.size() / (replicas + 1);
		log.debug("masters = {}", masters);
		if (masters < 3) {
			throw new RedisTribException("Invalid configuration for cluster creation. Redis Cluster requires at least 3 master nodes.", false);
		}
	}

	void addNodes(TribClusterNode node) {
		nodes.add(node);
	}

	void allocSlots() {
		int nodesCount = nodes.size();
		int mastersCount = nodesCount / (replicas + 1);
		List<TribClusterNode> masters = Lists.newArrayList();

		// The first step is to split instances by IP. This is useful as
		// we'll try to allocate master nodes in different physical machines
		// (as much as possible) and to allocate slaves of a given master in
		// different physical machines as well.
		// 
		// This code assumes just that if the IP is different, than it is more
		// likely that the instance is running in a different physical host
		// or at least a different virtual machine.
		Map<String, List<TribClusterNode>> tmpIps = nodes.stream().collect(Collectors.groupingBy(v -> v.getInfo().getHost(), Collectors.toList()));
		Map<String, List<TribClusterNode>> ips = Maps.newTreeMap();
		ips.putAll(tmpIps);
		for (Map.Entry<String, List<TribClusterNode>> e : ips.entrySet()) {
			List<TribClusterNode> hostNodes = e.getValue();
			hostNodes.sort((o1, o2) -> {
				if (StringUtils.equals(o1.getInfo().getHost(), o2.getInfo().getHost())) {
					return Integer.compare(o1.getInfo().getPort(), o2.getInfo().getPort());
				} else {
					return o1.getInfo().getHost().compareTo(o2.getInfo().getHost());
				}
			});
		}
		log.debug("ips={}", ips);

		// Select master instances
		List<TribClusterNode> interleaved = Lists.newArrayList();
		boolean stop = false;
		while (!stop) {
			for (Map.Entry<String, List<TribClusterNode>> e : ips.entrySet()) {
				List<TribClusterNode> hostNodes = e.getValue();

				if (hostNodes.isEmpty()) {
					if (interleaved.size() == nodesCount) {
						stop = true;
						continue;
					}
				} else {
					interleaved.add(hostNodes.remove(0));
				}
			}
		}

		masters = interleaved.subList(0, mastersCount);
		interleaved = interleaved.subList(mastersCount, interleaved.size());
		nodesCount = nodesCount - mastersCount;

		// Alloc slots on masters
		double slotsPerNode = CLUSTER_HASH_SLOTS / mastersCount;
		int first = 0;
		int last;
		double cursor = 0.0;
		for (int i = 0; i < masters.size(); i++) {
			last = (int)Math.round(cursor + slotsPerNode - 1.0);
			if (last > CLUSTER_HASH_SLOTS || i == masters.size() - 1) {
				last = CLUSTER_HASH_SLOTS - 1;
			}
			if (last < first) { // Min step is 1
				last = first;
			}

			List<Integer> slots = Lists.newArrayList();
			for (int j = first; j <= last; j++) {
				slots.add(j);
			}
			log.debug("add slots. first={}, last={}", first, last);
			masters.get(i).addTmpSlots(slots);
			first = last + 1;
			cursor = cursor + slotsPerNode;
		}

		// Select N replicas for every master.
		// We try to split the replicas among all the IPs with spare nodes
		// trying to avoid the host where the master is running, if possible.
		// 
		// Note we loop two times.  The first loop assigns the requested
		// number of replicas to each master.  The second loop assigns any
		// remaining instances as extra replicas to masters.  Some masters
		// may end up with more than their requested number of replicas, but
		// all nodes will be used.
		boolean assignmentVerbose = false;

		log.debug("masters={}", masters);
		log.debug("interleaved={}", interleaved);
	}

	@Override
	public void close() throws IOException {
		if (nodes != null) {
			nodes.forEach(v -> {
				try {
					v.close();
				} catch (Exception e) {
					log.error("Failed to close redis connection.");
				}
			});
		}
	}
}
