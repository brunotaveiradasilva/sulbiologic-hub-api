package com.almoxarifado.api.meta;

import jakarta.validation.constraints.NotNull;

/** true oculta a meta; false volta a mostrar. */
public record OcultarMetaRequest(
        @NotNull(message = "Informe se a meta fica oculta") Boolean oculta
) {
}
