/*
 * Copyright (c) 2011 Red Hat, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see 
 * <http://www.gnu.org/licenses>.
 */

package org.commonjava.poc.ral;

import org.apache.maven.mae.MAEException;

public class AppLauncherException
    extends MAEException
{

    private static final long serialVersionUID = 1L;

    public AppLauncherException( final String message, final Object... params )
    {
        super( message, params );
    }

    public AppLauncherException( final String message, final Throwable cause,
                                 final Object... params )
    {
        super( message, cause, params );
    }

    public AppLauncherException( final String message, final Throwable cause )
    {
        super( message, cause );
    }

    public AppLauncherException( final String message )
    {
        super( message );
    }

}
