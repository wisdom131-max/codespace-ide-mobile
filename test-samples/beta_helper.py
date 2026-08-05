"""beta_helper.py — Helper functions imported by alpha_main.py."""

from alpha_main import calculate_sum, calculate_product


def greet(name):
    """Greet someone by name."""
    return f"Hello, {name}!"


def double_value(value):
    """Double a numeric value."""
    return value * 2


def compute_average(numbers):
    """Compute the average of a list of numbers."""
    if not numbers:
        return 0
    total = sum(numbers)
    return total / len(numbers)


def process_data(data_list):
    """Process a list of data and return results."""
    results = []
    for item in data_list:
        doubled = double_value(item)
        results.append(doubled)
    return results
