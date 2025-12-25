"""
Test runner for photo geotagging tool.

Usage:
    python run_tests.py              # Run all tests
    python run_tests.py -v           # Verbose output
    python run_tests.py parsers      # Run only parser tests
    python run_tests.py matcher      # Run only matcher tests
    python run_tests.py integration  # Run only integration tests
"""

import sys
import unittest
from pathlib import Path

# Add current directory to path
sys.path.insert(0, str(Path(__file__).parent))

# Import test modules
import test_parsers
import test_matcher
import test_integration
import test_db_exporter
import test_data_integrity


def run_all_tests(verbosity=1):
    """Run all tests with specified verbosity."""
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    
    # Add all test modules
    suite.addTests(loader.loadTestsFromModule(test_parsers))
    suite.addTests(loader.loadTestsFromModule(test_matcher))
    suite.addTests(loader.loadTestsFromModule(test_integration))
    suite.addTests(loader.loadTestsFromModule(test_db_exporter))
    suite.addTests(loader.loadTestsFromModule(test_data_integrity))
    
    runner = unittest.TextTestRunner(verbosity=verbosity)
    result = runner.run(suite)
    
    return 0 if result.wasSuccessful() else 1


def run_specific_tests(test_name, verbosity=1):
    """Run specific test module."""
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    
    if test_name == 'parsers':
        suite.addTests(loader.loadTestsFromModule(test_parsers))
    elif test_name == 'matcher':
        suite.addTests(loader.loadTestsFromModule(test_matcher))
    elif test_name == 'integration':
        suite.addTests(loader.loadTestsFromModule(test_integration))
    elif test_name == 'exporter':
        suite.addTests(loader.loadTestsFromModule(test_db_exporter))
    elif test_name == 'integrity':
        suite.addTests(loader.loadTestsFromModule(test_data_integrity))
    else:
        print(f"Unknown test module: {test_name}")
        print("Available modules: parsers, matcher, integration, exporter, integrity")
        return 1
    
    runner = unittest.TextTestRunner(verbosity=verbosity)
    result = runner.run(suite)
    
    return 0 if result.wasSuccessful() else 1


def main():
    """Main test runner."""
    args = sys.argv[1:]
    verbosity = 2 if '-v' in args or '--verbose' in args else 1
    
    # Remove verbosity flags
    args = [a for a in args if a not in ['-v', '--verbose']]
    
    if not args:
        # Run all tests
        print("Running all tests...\n")
        return run_all_tests(verbosity)
    else:
        # Run specific test module
        test_name = args[0]
        print(f"Running {test_name} tests...\n")
        return run_specific_tests(test_name, verbosity)


if __name__ == '__main__':
    sys.exit(main())
