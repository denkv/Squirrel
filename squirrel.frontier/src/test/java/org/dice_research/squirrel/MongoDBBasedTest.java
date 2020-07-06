package org.dice_research.squirrel;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

public class MongoDBBasedTest {

	public static final String DB_HOST_NAME = "localhost";
    public static final int DB_PORT = 58027;

    protected static  MongoClient client;
	protected static  MongoDatabase mongoDB;

    @BeforeClass
    public static void setUpMDB() throws Exception {
        String mongoDockerExecCmd = "docker run --name squirrel-test-mongodb "
            + "-p 58027:27017 -p 58886:8080 -d mongo:4.0.0";
        Process p = Runtime.getRuntime().exec(mongoDockerExecCmd);
        BufferedReader stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String s = null;
        while ((s = stdInput.readLine()) != null) {
            System.out.println(s);
        }
        // read any errors from the attempted command
        BufferedReader stdError = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        System.out.println("Here is the standard error of the command (if any):\n");
        while ((s = stdError.readLine()) != null) {
            System.out.println(s);
        }

        client = new MongoClient(DB_HOST_NAME,DB_PORT);

    }

    @AfterClass
    public static void tearDownMDB() throws Exception {
        String mongoDockerStopCommand = "docker stop squirrel-test-mongodb";
        Process p = Runtime.getRuntime().exec(mongoDockerStopCommand);
        p.waitFor();
        String mongoDockerRmCommand = "docker rm squirrel-test-mongodb";
        p = Runtime.getRuntime().exec(mongoDockerRmCommand);
        p.waitFor();
    }
}
