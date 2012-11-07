REPO=$HOME/.m2/repository
SCALA=$REPO/org/apache/servicemix/bundles/org.apache.servicemix.bundles.scala-library/2.9.1_3/org.apache.servicemix.bundles.scala-library-2.9.1_3.jar
CLASSPATH=$SCALA:$REPO/org/apache/felix/org.apache.felix.main/4.0.3/org.apache.felix.main-4.0.3.jar:sample/target/servicediagnostics.sample-0.1.1-SNAPSHOT.jar
#scala 
java -classpath $CLASSPATH org.apache.felix.servicediagnostics.sample.FelixLauncher \
  $SCALA\
  core/target/org.apache.felix.servicediagnostics.plugin-0.1.2-SNAPSHOT.jar\
  sample/target/servicediagnostics.sample-0.1.1-SNAPSHOT.jar\
  $REPO/org/apache/felix/org.apache.felix.main/4.0.3/org.apache.felix.main-4.0.3.jar\
  $REPO/org/apache/felix/org.apache.felix.dependencymanager/3.0.0/org.apache.felix.dependencymanager-3.0.0.jar\
  $REPO/org/apache/felix/org.apache.felix.dependencymanager.shell/3.0.0/org.apache.felix.dependencymanager.shell-3.0.0.jar\
  $REPO/org/apache/felix/org.apache.felix.scr/1.6.0/org.apache.felix.scr-1.6.0.jar\
  $REPO/org/osgi/org.osgi.compendium/4.2.0/org.osgi.compendium-4.2.0.jar\
  $REPO/org/apache/felix/org.apache.felix.http.jetty/2.2.0/org.apache.felix.http.jetty-2.2.0.jar\
  $REPO/org/apache/felix/org.apache.felix.webconsole/4.0.0/org.apache.felix.webconsole-4.0.0.jar\
  $REPO/org/apache/felix/org.apache.felix.shell/1.4.3/org.apache.felix.shell-1.4.3.jar\
  $REPO/org/apache/commons/com.springsource.org.apache.commons.fileupload/1.2.1/com.springsource.org.apache.commons.fileupload-1.2.1.jar\
  $REPO/org/apache/commons/com.springsource.org.apache.commons.io/1.4.0/com.springsource.org.apache.commons.io-1.4.0.jar\
  $REPO/org/apache/geronimo/bundles/json/20090211_1/json-20090211_1.jar\
  $REPO/org/apache/felix/org.apache.felix.webconsole.plugins.shell/1.0.0-SNAPSHOT/org.apache.felix.webconsole.plugins.shell-1.0.0-SNAPSHOT.jar

 
