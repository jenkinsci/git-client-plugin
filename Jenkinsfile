#!groovy

// build recommended configurations
subsetConfiguration = [ [ jdk: '8',  platform: 'windows', jenkins: null                      ],
                        [ jdk: '8',  platform: 'linux',   jenkins: '2.164.1', javaLevel: '8' ],
                        [ jdk: '11', platform: 'linux',   jenkins: '2.164.1', javaLevel: '8' ]
                      ]

buildPlugin(configurations: subsetConfiguration, failFast: false)
