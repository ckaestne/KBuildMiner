/*
 * Copyright (c) 2011 Leonardo Passos <lpassos@gsd.uwaterloo.ca>
 *
 * This file is part of iml-parser.
 *
 * Iml-parser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Iml-parser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Iml-parser.  If not, see <http://www.gnu.org/licenses/>.
 */

package gsd.iml.ast.constraint;

import gsd.iml.ast.expression.Expression;
import java.util.Arrays;

/**
 *
 * @author leonardo
 */
public class UnaryImlConstraint extends ImlConstraint {
    private Expression expression ;

    public UnaryImlConstraint(String name, Expression expression) {
        super(name, Arrays.asList(expression)) ;
        this.expression = expression ;
    }

    public Expression getExpression() {
        return expression ;
    }
}
