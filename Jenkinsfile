#!groovy

Random random = new Random() // Randomize which Jenkins version is selected for more testing
def use_newer_jenkins = random.nextBoolean() // Use newer Jenkins on one build but slightly older on other

// build recommended configurations
subsetConfiguration = [ [ jdk: '8',  platform: 'windows', jenkins: null                      ],
                        [ jdk: '8',  platform: 'linux',   jenkins: !use_newer_jenkins ? '2.176.3' : '2.164.1', javaLevel: '8' ],
                        [ jdk: '11', platform: 'linux',   jenkins:  use_newer_jenkins ? '2.176.3' : '2.164.1', javaLevel: '8' ]
                      ]

buildPlugin(configurations: subsetConfiguration, failFast: false)
