# Integration tests for [matchbox](https://github.com/ahdis/matchbox)

This project contains a JUnit test bench for matchbox.

This project expects the `matchbox` project to be installed in the local Maven directory, and the `matchbox-server`
module must be installed without the `boot` Maven profile, i.e.:
`mvn -DskipTests -P !boot install`
zsh:  `mvn -DskipTests -P \!boot install`

Then, these tests must be run with the matchbox version as parameter: `mvn -D'matchbox.version=3.9.13' clean site`
or `mvn clean site`, then you can find the result in target/site/index.html
