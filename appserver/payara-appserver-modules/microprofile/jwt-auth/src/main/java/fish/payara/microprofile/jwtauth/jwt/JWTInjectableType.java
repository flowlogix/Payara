/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2017-2021] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/main/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.microprofile.jwtauth.jwt;

import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toSet;
import static jakarta.json.Json.createArrayBuilder;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.eclipse.microprofile.jwt.ClaimValue;

/**
 * This class keeps track of the plethora of types and all their permutations by which
 * basic claim values can be injected.  
 * 
 * @author Arjan Tijms
 */
public class JWTInjectableType {

    private Type fullType;
    private Class<?> coreClass;
    private boolean optional;
    private boolean claimValue;
    private Function<JsonValue, Object> converter;

    public JWTInjectableType(Type fullType) {
        this.fullType = fullType;
        coreClass = (Class<?>) fullType;
        installCoreConverter();
    }

    public JWTInjectableType(Type fullType, Class<?> coreClass) {
        this.fullType = fullType;
        this.coreClass = coreClass;
        installCoreConverter();
    }

    public JWTInjectableType(Type fullType, JWTInjectableType previous) {
        this.fullType = fullType;
        this.coreClass = previous.getCoreClass();
        this.optional = previous.isOptional() ? true : isTypeOptional(fullType);
        this.claimValue = isTypeClaimValue(fullType);
        installCoreConverter();
    }

    public Object convert(JsonValue value) {
        if (value == null) {
            return null;
        }
        
        return converter.apply(value);
    }

    public Type getFullType() {
        return fullType;
    }

    public Class<?> getCoreClass() {
        return coreClass;
    }

    public boolean isOptional() {
        return optional;
    }

    public boolean isClaimValue() {
        return claimValue;
    }

    @Override
    public String toString() {
        return "fullType: " + fullType + " coreClass: " + coreClass + " optional: " + optional + " claimValue: " + claimValue;
    }

    private boolean isTypeOptional(Type type) {
        return Optional.class.isAssignableFrom(getClassFromType(fullType));
    }

    private boolean isTypeClaimValue(Type type) {
        return ClaimValue.class.isAssignableFrom(getClassFromType(fullType));
    }

    private Class<?> getClassFromType(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }

        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }

        return null;
    }

    private void installCoreConverter() {
        if (coreClass == String.class) {
            converter = e -> ((JsonString) e).getString();
        } else if (coreClass == Set.class) {
            converter = e -> convertToSet(e);
        } else if (coreClass == Long.class) {
            converter = e -> ((JsonNumber) e).longValue();
        } else if (coreClass == Boolean.class) {
            converter = e -> convertToBoolean(e);
        } else if (coreClass == JsonArray.class) {
            converter = e -> e instanceof JsonArray ? e : createArrayBuilder().add(e).build();
        } else {
            converter = e -> e;
        }
    }
    
    private static Set<String> convertToSet(JsonValue jsonValue) {
        if (jsonValue instanceof JsonArray) {
            return new HashSet<>(((JsonArray) jsonValue).getValuesAs(JsonString.class)).stream().map(t -> t.getString()).collect(toSet());
        }
        
        return singleton(((JsonString) jsonValue).getString());
    }

    private static boolean convertToBoolean(JsonValue jsonValue) {
        if (jsonValue instanceof JsonString) {
            return Boolean.parseBoolean(((JsonString) jsonValue).getString());
        }

        return Boolean.parseBoolean(jsonValue.toString());
    }

}