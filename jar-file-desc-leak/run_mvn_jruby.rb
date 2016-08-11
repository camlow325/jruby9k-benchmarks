#!/usr/bin/env ruby

subcommand_output = 'file_desc_leak_subcommand_output.txt'
benchmark_output = 'file_desc_leak_output.txt'

benchmark_output_files = [benchmark_output ,
                          subcommand_output]
benchmark_output_files.each do |f|
  if File.exists?(f)
    puts "Removing old benchmark output ('#{f}')"
    File.delete(f)
  end
end

puts "Logging subcommand output to: #{subcommand_output}"
open(subcommand_output, 'w') do |f|
  puts "Cleaning."
  f.puts `mvn clean`
  puts "Compiling."
  f.puts `mvn compile`
  puts "Running."
  f.puts `MAVEN_OPTS="-Xms14g -Xmx14g" mvn exec:java -Dexec.mainClass=jruby9k.FileDescLeakDriver`
end

puts "Output available in '#{benchmark_output}'"
