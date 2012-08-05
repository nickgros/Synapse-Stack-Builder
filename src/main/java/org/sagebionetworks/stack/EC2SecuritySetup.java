package org.sagebionetworks.stack;

import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;
import org.sagebionetworks.stack.config.InputConfiguration;

import static org.sagebionetworks.stack.Constants.*;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsRequest;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.SecurityGroup;

/**
 * Setup the security used by the rest of the stack.
 * 
 * @author jmhill
 *
 */
public class EC2SecuritySetup {
	
	private static Logger log = Logger.getLogger(EC2SecuritySetup.class.getName());
	
	private AmazonEC2Client ec2Client;
	private InputConfiguration config;
	private GeneratedResources resources;
	/**
	 * IoC constructor.
	 * @param ec2Client
	 * @param config
	 * @param resoruces 
	 */
	public EC2SecuritySetup(AmazonEC2Client ec2Client, InputConfiguration config, GeneratedResources resources) {
		super();
		if(ec2Client == null) throw new IllegalArgumentException("AmazonEC2Client cannot be null");
		if(config == null) throw new IllegalArgumentException("Config cannot be null");
		if(resources == null) throw new IllegalArgumentException("GeneratedResources cannot be null");
		this.ec2Client = ec2Client;
		this.config = config;
		this.resources = resources;
	}

	/**
	 * Create the EC2 security group that all elastic beanstalk instances will belong to.
	 * 
	 * @param ec2Client - valid AmazonEC2Client
	 * @param stack - The name of this stack.
	 * @param instance - The name of this stack instance.
	 * @param cidrForSSH - The classless inter-domain routing to be used for SSH access to these machines.
	 * @return
	 */
	public SecurityGroup setupElasticBeanstalkEC2SecutiryGroup(){
		CreateSecurityGroupRequest request = new CreateSecurityGroupRequest();
		request.setDescription(config.getElasticSecurityGroupDescription());
		request.setGroupName(config.getElasticSecurityGroupName());
		createSecurityGroup(request);
		//Setup the permissions for this group:
		// Allow anyone to access port 80 (HTTP)
		addPermission(request.getGroupName(), new IpPermission().withIpProtocol(IP_PROTOCOL_TCP).withFromPort(PORT_HTTP).withToPort(PORT_HTTP).withIpRanges(CIDR_ALL_IP));
		// Allow anyone to access port 443 (HTTPS)
		addPermission(request.getGroupName(), new IpPermission().withIpProtocol(IP_PROTOCOL_TCP).withFromPort(PORT_HTTPS).withToPort(PORT_HTTPS).withIpRanges(CIDR_ALL_IP));
		// Only allow ssh to the given address
		addPermission(request.getGroupName(), new IpPermission().withIpProtocol(IP_PROTOCOL_TCP).withFromPort(PORT_SSH).withToPort(PORT_SSH).withIpRanges(config.getCIDRForSSH()));
		// Return the group name
		DescribeSecurityGroupsResult result = ec2Client.describeSecurityGroups(new DescribeSecurityGroupsRequest().withGroupNames(request.getGroupName()));
		if(result.getSecurityGroups() == null || result.getSecurityGroups().size() != 1) throw new IllegalStateException("Did not find one and ony one EC2 secruity group with the name: "+request.getGroupName());
		// Add this to the resources
		SecurityGroup group = result.getSecurityGroups().get(0);
		resources.setElasticBeanstalkEC2SecurityGroup(group);
		
		// Create the key pair.
		resources.setStackKeyPair(createOrGetKeyPair());
		
		return group;
	}
	
	/**
	 * Create the key par
	 * @return
	 */
	public KeyPairInfo createOrGetKeyPair(){
		String name =config.getStackKeyPairName();
		try{
			DescribeKeyPairsResult result =ec2Client.describeKeyPairs(new DescribeKeyPairsRequest().withKeyNames(name));
			if(result.getKeyPairs().size() != 1) throw new IllegalStateException("Expceted one and only one key pair with the name: "+name);
			log.debug("Stack KeyPair: "+name+" already exists");
			return result.getKeyPairs().get(0);
		}catch (AmazonServiceException e){
			if(Constants.ERROR_CODE_KEY_PAIR_NOT_FOUND.equals(e.getErrorCode())){
				// Create the key pair
				log.debug("Creating the Stack KeyPair: "+name+" for the first time");
				ec2Client.createKeyPair(new CreateKeyPairRequest(name));
				DescribeKeyPairsResult result =ec2Client.describeKeyPairs(new DescribeKeyPairsRequest().withKeyNames(name));
				if(result.getKeyPairs().size() != 1) throw new IllegalStateException("Expceted one and only one key pair with the name: "+name);
				return result.getKeyPairs().get(0);
			}else{
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Create a security group. If the group already exists
	 * @param ec2Client
	 * @param request
	 */
	void createSecurityGroup(CreateSecurityGroupRequest request) {
		try{
			// First create the EC2 group
			log.info("Creating Security Group: "+request.getGroupName()+"...");
			CreateSecurityGroupResult result = ec2Client.createSecurityGroup(request);
		}catch (AmazonServiceException e){
			if(ERROR_CODE_INVALID_GROUP_DUPLICATE.equals(e.getErrorCode())){
				// This group already exists
				log.info("Security Group: "+request.getGroupName()+" already exits");
			}else{
				throw e;
			}
		}
	}
	
	/**
	 * Add a single permission to the passed group.  If the permission already exists, this will be a no-operation.
	 * @param ec2Client
	 * @param groupName
	 * @param permission
	 */
	void addPermission(String groupName, IpPermission permission){
		// Make sure we can access the machines from with the VPN
		try{
			List<IpPermission> permissions = new LinkedList<IpPermission>();
			permissions.add(permission);
			// Configure this group
			AuthorizeSecurityGroupIngressRequest ingressRequest = new AuthorizeSecurityGroupIngressRequest(groupName, permissions);
			log.info("Adding IpPermission to group: '"+groupName+"'...");
			log.info("IpPermission: "+permission.toString()+"");
			ec2Client.authorizeSecurityGroupIngress(ingressRequest);
		}catch(AmazonServiceException e){
			// Ignore duplicates
			if(ERROR_CODE_INVALID_PERMISSION_DUPLICATE.equals(e.getErrorCode())){
				// This already exists
				log.info("IpPermission: "+permission.toString()+" already exists for '"+groupName+"'");
			}else{
				// Throw any other error
				throw e;
			}
		}
	}
	

}
