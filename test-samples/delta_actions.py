"""delta_actions.py — Actions module for testing Find References and Rename Symbol."""

from alpha_main import calculate_sum, Calculator


def perform_addition(x, y):
    """Use calculate_sum from alpha_main."""
    result = calculate_sum(x, y)
    return result


def create_calculator():
    """Create a Calculator instance from alpha_main."""
    calc = Calculator()
    return calc


def run_calculations():
    """Run multiple calculations."""
    calc = create_calculator()
    value1 = perform_addition(10, 20)
    calc.add(value1, 5)
    return calc.result
