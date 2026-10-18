require 'xcodeproj'
root = File.expand_path(__dir__)
project = Xcodeproj::Project.open(File.join(root, 'ios/App/App.xcodeproj'))
app = project.targets.find { |target| target.name == 'App' }
target = project.new_target(:ui_test_bundle, 'OrkaUITests', :ios, '16.4')
target.add_dependency(app)
group = project.main_group.new_group('OrkaUITests', '../../')
file = group.new_file('OrkaUITests.swift')
target.source_build_phase.add_file_reference(file)
target.build_configurations.each do |configuration|
  configuration.build_settings.merge!({
    'GENERATE_INFOPLIST_FILE' => 'YES', 'SWIFT_VERSION' => '5.0',
    'PRODUCT_NAME' => 'OrkaUITests',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.lytspeed.orka.uitests',
    'TEST_TARGET_NAME' => 'App', 'CODE_SIGNING_ALLOWED' => 'NO'
  })
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_test_target(target)
scheme.set_launch_target(app)
scheme.save_as(project.path, 'OrkaSideload')
puts 'Added the native iOS UI smoke tests.'
